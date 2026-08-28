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
> - [`branch-aware-executor-plan.md`](../archived-documents/plans-archive/branch-aware-executor-plan.md) — its §3 operator model
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

> **Phase 5 progress (2026-08-10 — supersedes the 2026-08-06 note, which was stale):** `step-types`
> endpoint + UI slices S1–S4 SHIPPED 2026-08-06, and **S5 (grid editors) and S6 (Pipeline Document +
> mapping import loop, incl. the dry-run follow-up and UI wiring) shipped 2026-08-06 as well** — the
> earlier note listed both as remaining. **Phase 5's verify gate is now met, with its "specs for all
> seven verbs" clause read as follows (2026-08-10):**
>
> - `parse` and `map` serve `attributes: []` **deliberately** — each has a richer editor than a scalar
>   form (Grammar dialog; the mapping-CSV surface), so a spec there would be a worse second way to
>   author the same thing. ⚠ Until 2026-08-10 this reinterpretation of the gate lived **only** in a
>   comment in `StepTypesContractTest`, nowhere in this plan; it is recorded here now because a reader
>   checking the gate against §5's literal "all seven" would otherwise call Phase 5 unfinished.
> - The palette publishes **nine** entries, not seven: `route` is its own entry (added S3/S4), and
>   `transform` appears **twice** — once per shape it authors (`transform.filter`, `transform.join`).
>   §1.2's "closed built-in Step set" describes the **recipe grammar**, which is unchanged; the palette
>   is per SHAPE because one verb legitimately authors two node types.
> - ⚠ **A join Step was unauthorable from the recipe editor until 2026-08-10.** Phase 3 S2 shipped join
>   *compiling*, and `NodeAttributes` had a `transform.join` spec, but `RECIPE_VERBS` mapped the
>   `transform` verb to `transform.filter` alone, so `step-types` never served the join spec and the
>   Add-Step menu never offered it — authorable only from the **demoted** canvas, which loads the full
>   `node-types` catalog. Closed by a second `transform` entry (below).

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
The UI slices are planned separately in [`elt-amendment-ui-plan.md`](../archived-documents/plans-archive/elt-amendment-ui-plan.md)
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

#### Phase 2 GROUNDED 2026-08-05 — findings that shape the slices

1. **The recipe compile target is `PipelineEditable.lower` over a `PipelineGraph`** — build one node
   per Step in chain order and every existing refusal/validation gate (`LOWERABLE`, `NO_ACQUISITION`,
   `PARSER_NO_SCHEMA`, `ConfigSpecs.pipeline()` + `ConfigSafetyValidator`) comes for free
   (`PipelineEditable.java:44-48/:207`, `PipelineRoutes.saveGraph:184-239`). Verb→node: collect →
   `acquisition`, parse → `parser`, dedup → `transform.dedup_marker`, transform.filter →
   `transform.filter`, sink → `sink.persistent` (+ the plural `sinks:` list). **`route` is not
   lowerable today** (explicitly refused `UNSUPPORTED_NODE`) and **no `summarize` node kind exists** —
   the latter is Phase 3's verb anyway, so the Phase-2 compiler covers the linear file-entry verbs
   and `route` lands as its own later slice.
2. **Discovery keys on the `_pipeline.toon` suffix** (`ConfigRegistry.PIPELINE_SUFFIX:41`); a bare
   `pipelines/<name>.toon` recipe is invisible. **Decision of record: the compiler writes a canonical
   `<name>_pipeline.toon`** (compile-at-authoring, discovery unchanged) rather than teaching the
   registry a second suffix.
3. **The recipe `guarantees:` block is richer than `source.guarantee`** (a single enum,
   `PipelineConfig.java:317-338`) — the fold is Phase 4's deliverable (§2.4); Phase 2's compiler
   passes through what exists and refuses what doesn't, loudly.
4. **Type flow is a small extraction, not new machinery.** `DataTransformer.materialize` builds its
   full SELECT *before* wrapping it in `CREATE TABLE AS` (`:107-110`) — expose the SELECT and wrap it
   in `DESCRIBE (…)` over a throwaway `DuckDbUtil` connection (the `SchemaExtractor:79-155` /
   `ComponentPreview` precedent). `SchemaExtractor.mapType(:355)` is the one existing DuckDB→schema
   type mapping. Footer parity for the verify gate: `PartitionWriter` writes Parquet via
   `COPY (SELECT …)` (`:83-111`), so the footer schema is exactly the SELECT's inferred types —
   readable back with `parquet_schema()` / `DESCRIBE (SELECT * FROM read_parquet(…))`, zero new deps.
5. **The §5.6 "pinned config" precedent does not exist in code** — the fingerprint is the FIRST
   pinning, not a follow-on. Seams: `BatchManifest` (Gson POJO, null-tolerant on old files — the
   `consignmentId`/`batchId` alias precedent), populated in `BatchProcessor.finalizeSource:178-189`;
   `ConsignmentOutput` + `DbConsignmentOutputStore.initSchema:69-79` (schema-on-open, **no ALTER
   migration precedent** — needs `ALTER TABLE … ADD COLUMN IF NOT EXISTS`). `cfg.referencedFiles()`
   (`PipelineConfig.java:698,745`) is exactly the schema+mapping file set to hash; **no shared
   content-hash utility is reachable from etl/engine** (`ContentHash` is package-private in
   `com.gamma.control`) — a small SHA-256 helper lands in `inspecto-util`.

**Slices of record (each shippable):** **S1** fingerprint pinning (manifest + `consignment_outputs`
column + shared hash util) · **S2** per-Step type flow (`DESCRIBE`-derived output schemas + the
footer-parity gate + save-time cell errors) · **S3** recipe format + compiler (linear verbs →
`PipelineGraph` → `lower` → `<name>_pipeline.toon`) · **S4** read-side converter + fixture
round-trip parity. `route` compilation follows S3 once a lowerable route node exists.

**P2 S1 SHIPPED 2026-08-05** — the §3.4.3 schema fingerprint: `com.gamma.util.CanonicalHash`
(SHA-256 over a canonical rendering — map keys sorted, scalars length-prefixed);
`BatchManifest.schemaFingerprint` (Gson-tolerant, null on old manifests);
`ConsignmentOutput.schemaFingerprint` + `consignment_outputs.schema_fingerprint` (DDL widened,
`ADD COLUMN IF NOT EXISTS` migration on open, old rows read null); pinned in
`BatchProcessor.finalizeSource` from the resolved schema map (mapping rules included — the parser
merged them at load), located by `batch.schemaName()` across single/selector/segments. The
enrichment/Pipeline-sink paths deliberately record null until S2's derived output schemas exist.

**P2 S2 SHIPPED 2026-08-05** — per-Step type flow: `DataTransformer.selectFor` extracted (pure SQL
text, byte-identical assembly); `TypeFlow` (inspecto-etl) `DESCRIBE`s that SELECT over an empty
scratch table shaped like the raw ingest table (CSV path = all-VARCHAR, plugin path = declared
types) — deriving `transformedColumns`/`sinkColumns` without executing, and failing at authoring
time with DuckDB's binder error naming the column for a mapping over a nonexistent field.
**Footer-parity gate holding:** `TypeFlowTest.derivedSinkSchemaMatchesTheWrittenParquetFooter`
materializes + `PartitionWriter.write`s real rows and compares the derived sink schema to
`read_parquet(…, hive_partitioning=false)` — equal name-for-name, type-for-type. (Gotcha for
later fixtures: default `read_parquet` re-derives partition columns from the Hive path; the footer
itself carries sink columns minus partitions.) Save-time/dry-run wiring of these derivations into
ConfigRoutes/PipelineDryRun deliberately rides S3+ (needs the recipe/pipeline context, not the
schema component alone).

**P2 S3 SHIPPED 2026-08-05** — `RecipeCompiler` (inspecto-engine): the linear recipe
(`name/trigger/steps/guarantees`) compiles by building one `PipelineNode` per Step in list order
and delegating to `PipelineEditable.lower`, so every existing refusal/completeness gate applies
(proved in test: an active recipe missing parse/sink surfaces `NO_PARSER`/`NO_PERSISTENT_SINK`).
Verbs this slice: `collect` (connection→`use:`, files→file_pattern, recipe `trigger` rides the
entry node — §2.7), `parse` (grammar→`use:`, other keys verbatim in `parsing:`), `map` (FOLDS into
the parser node — `schema/`+`mapping/` registry refs onto `processing.schema_file/mapping_file`;
plural spellings normalised), `transform.filter` (→ `csv_settings.where`), `sink` (verbatim;
multi-destination rides the existing `sinks:` lowering). NOT compilable, refused with named codes
(never dropped): `dedup` (QUALIFY lowering post-S3), `route`, `summarize` (Phase 3), non-empty
`guarantees:` (Phase 4), `transform.join/derive`. Amendment ridden in: **`mapping_file` added to
`PipelineEditable.PARSER_OWNED`** — without it every graph save dropped the new slice-3 key.
Compiled config proven executable: written via `ConfigCodec.toToon` and loaded by
`PipelineConfig.load` with the `schema/<id>` ref resolving. Discovery per grounding decision:
compile to `<name>_pipeline.toon`; no new suffix. Control-plane route + UI editor ride the UI plan.

**P2 S4 SHIPPED 2026-08-05** — `RecipeConverter` (inspecto-engine) projects a decoded canonical
config into the recipe shape; the round-trip half is `RecipeCompiler.compile(recipe, existing,
lenient)` — lower's ownership rule preserves everything the recipe does not model (markers, gap
watch, dirs.errors…), so the converter never widens the recipe vocabulary to survive a fixture.
**Parity gate green over the real corpus:** `RecipeConverterTest` walks every repo
`spaces/**/*_pipeline.toon` (6 fixtures, incl. multi-schema voucher + plugin subscriber) and
asserts `compile(toRecipe(cfg), cfg, false)` == original. Two compiler amendments ridden in:
parse carries parser-owned processing keys on the node (not inside `parsing:`), and the
converter's sink step carries the sink-owned write-tuning keys (`threads`, `duckdb_threads`,
`batch_max_*`) — the gate caught both as real drops.

> 🔴 **Amended 2026-08-18 (`f72f7fc8`): the corpus gate is only as wide as the corpus, and S4 shipped
> blind to the `steps:` spelling.** The converter synthesised its transform steps solely from the legacy
> singular blocks, which an explicit-`steps:` file never carries (the parser refuses both spellings in
> one file) — so such a config projected an **empty chain in silence**. It went unnoticed for 13 days
> because no fixture used that spelling; it turned the gate red the moment `ae2c0909` authored one
> (`spaces/demo/config/orders/orders_pipeline.toon`). The fix also needed a second half in
> `PipelineEditable.lower`, which chose the chain spelling from the graph shape alone and so renormalised
> an authored `steps:` file back to the singular keys — exactness was unreachable without it. As-built
> in `okf/backend/pipeline-graph/pipeline-graph-design.md` §16. **Two standing lessons for the remaining
> phases: a fixture-corpus parity gate proves nothing about a shape no fixture uses, and every reader
> of a multi-spelling key must be checked against EVERY spelling before it is called lossless.**

**P2 S5 SHIPPED 2026-08-06** — `route` + `dedup` (QUALIFY) lowering, closing Phase 2's two
remaining verbs. `dedup` shipped as full lowering **and** real execution in the same slice
(avoiding the W1 dead-config trap): new `BuiltinNodeType.TRANSFORM_DEDUP` node kind; flat home
`processing.dedup {keys[], order_by}` (keys validated against `declaredColumns`, same posture as
`reference.key`); `BatchIngestStrategy.writeAndTrace` runs a `QUALIFY ROW_NUMBER() OVER (PARTITION
BY <keys> [ORDER BY order_by]) = 1` between transform materialisation and reference-versioning/the
partitioned write, logging dropped-duplicate counts. `route` shipped as lowering/round-trip **only**
with a fail-closed arming gate: `PipelineConfig.prepare()` throws for an `active` pipeline carrying
`route:` (`BatchGraphRunner`, the only thing that could execute a branch tree, has no production
call site — grounded, not assumed), same posture as the schema-less-draft rule. The `route:` block
is carried verbatim (RowShaper shape: `mode`, `branches:[{key, where?, database}]`, top-level
`default:`); branch↔sink pairing survives the flat file (which has no edges) by stamping each
branch's `database` at lower time and re-deriving `route:<key>` edges at lift time from a matching
sink's `database`. `RecipeCompiler` compiles both verbs (`route` v1: each branch's steps = exactly
one `sink` step, `route` ends the trunk per §2.6, `keep:` other than `first` refused — `order_by`
picks the winner); `RecipeConverter` projects both back losslessly. **Phase 2 is now fully closed**;
remaining pre-Phase-3 loose ends — TypeFlow save/dry-run wiring and the `/pipelines/{id}` recipe
route — are UI-adjacent plumbing, not compiler/lowering work, and ride the UI plan (S1–S3 unblocked
in parallel).

---

#### Phase 3 GROUNDED 2026-08-06 — findings that shape the slices

1. **`summarize` is explicitly refused today** (`RecipeCompiler.java:113-114`, `"summarize is Phase
   3's verb"`) — no node kind exists. `MaterializeTask` (`inspecto-engine/.../job/MaterializeTask.java:28-165`)
   is the at-rest runtime: a `JobConfig`-driven maintenance task (`dataset/target/measures/group_by/limit`)
   that compiles group-by + measures via `MeasureCompiler.parse/compile` (`:122-141`, the same BI-7
   grammar a dataset-scope report uses), stage-and-swaps to Parquet, registers a Dataset. It is **not**
   Pipeline-shaped — it runs over a `DatasetRelation.relationSql`, with no `PipelineConfig`/recipe
   involvement, so a `summarize` node's natural config (`processing.summarize {group_by[], measures[]}`)
   can reuse `MeasureCompiler`'s grammar verbatim (mirrors how dedup's Phase-2 slice reused `QUALIFY`)
   but compiling it does not by itself make it executable — wiring the recipe into `MaterializeTask`'s
   runtime is separate work, same posture as Phase 2's `route` arming gate.
2. **§2's "in-motion" half of `summarize` (`SummaryWriter`) is a weaker seam than the plan assumes.**
   `SummaryWriter` (`inspecto-engine/.../consignment/SummaryWriter.java:26-70`, §7.3) writes
   per-Consignment `SummaryRow`s into `_summaries/<target>/` with a composability sidecar
   (`_measures.csv`, so averages can't be re-summed), but it is invoked from consignment-processing
   code (a `GuardedSummaryEmitter`), not from any Pipeline node — there is no existing call site where
   a Pipeline emits into it. The "in-motion" tier is aspirational, not a seam to wire against yet.
3. **Enrichment is a distinct node kind, not a `transform` variant, and has no compiler verb.**
   `BuiltinNodeType.ENRICHMENT` (`:79-81`) is already in `PipelineEditable.LOWERABLE`
   (`PipelineEditable.java:44-50`) — added ahead of compiler support — but `RecipeCompiler`'s verb
   switch (`:18-22,96-116`) has no `enrich`/`transform.join` case; an `enrich` step today hits the
   generic unknown-verb refusal. §2's "Enrichment retired, join model lives inside `transform`" is
   aspirational: `EnrichmentEngine` (`inspecto-engine/.../enrich/EnrichmentEngine.java:51-110`) runs a
   distinct Stage-2 SQL join over Stage-1 partitions (`references[]` as DuckDB views + a `transform`
   SQL string), driven by `EnrichmentConfig` — folding that into `transform.filter/derive`'s existing
   SQL machinery is real design work, not plumbing. Decision needed before compiling: keep `enrich` as
   its own verb (lower risk, matches the existing distinct node kind) vs. fold into `transform.join`
   (matches §2's vocabulary table but requires a semantics merge).
4. **Table-entry `collect` has zero existing code — the highest-risk item.** `collect` in
   `RecipeCompiler` always builds an `ACQUISITION` node (files/Connection); nothing publishes a
   Table-write Signal a Pipeline could subscribe to. The closest machinery is `pipeline.commit`
   (`JobService.java:233,278,283,516-527`, mirrored from every committed `BatchEvent`) and
   `consignment.process` (`ConsignmentProcessJobType.java:22-41,84`, a Job Type resolved from that
   Signal) — both fire *after* a file-based commit, never from a bare Table read. `EnrichJob`
   (`inspecto-engine/.../job/EnrichJob.java:56`) is the existing wrapper that calls `EnrichmentEngine`
   and publishes a `BatchEvent` — the thing a table-entry Pipeline's `collect` would need to become or
   replace. §2's "table-entry rides the Signal bus (§4.2)" is unimplemented in both directions.

**Slices of record:** **S1** `summarize` node kind + at-rest lowering only (compile-only, no new
executor — `MaterializeTask` stays the runtime until wired, same posture as Phase 2's `route`) · **S2**
enrichment verb onto `RecipeCompiler` (wires the already-`LOWERABLE` `ENRICHMENT` node into the verb
switch; join-config shape is a real decision, not just glue) · **S3** table-entry `collect` + Signal
wiring (largest-risk slice — no existing partial implementation to grow from; a design spike may
precede slicing further) · **S4** fixture parity gate — convert a real `*_enrich.toon` + a
`materialize` task to recipes, assert identical `EnrichmentEngine`/`MaterializeTask` output, closing
the Phase-3 verify gate.

**P3 S1 SHIPPED 2026-08-06** — `summarize` compiles: `BuiltinNodeType.TRANSFORM_SUMMARIZE`
(`transform.summarize`) joins `PipelineEditable.LOWERABLE`; flat home `processing.summarize
{group_by[], measures[]}` (`group_by` validated against `declaredColumns`, same posture as
`reference.key`/`processing.dedup`; `measures` reuses `MaterializeTask`'s shorthand grammar verbatim
— `count`, `sum(amount)`, …, so a future wiring slice is byte-compatible with the existing
`materialize` maintenance-task params). `PipelineLift` emits the node between dedup and any route;
`RecipeCompiler` compiles a `summarize:` step to the node; `RecipeConverter` projects it back.
**Same fail-closed posture as `route`:** `PipelineConfig.prepare()` refuses an `active` pipeline
carrying `processing.summarize` — `MaterializeTask` runs over a Dataset relation on its own schedule,
not this pipeline's linear ingest path, so arming it would be dead config the engine silently ignores
(the W1 lesson, again). Compile-only; no new executor in this slice. Tests: `RecordDedupRouteConfigTest`
(+3), `RecipeCompilerTest` (+2), `RecipeConverterTest` (extended fixture),
`NodeConfigNameContractTest` (+1 pinned-lowerable flip, +1 draft-path test). Full reactor green.
**Phase 3 remaining:** S2 (enrichment verb), S3 (table-entry `collect` + Signal wiring — the
design-spike item), S4 (fixture parity gate).

**P3 S2 SHIPPED 2026-08-06** — the reference join compiles, per D-4's one-verb spelling
(`transform: {join: references/x, on: k}`; `on: k` is the single-key shorthand, a list otherwise).
**Grounding correction ridden in:** the slice proposal said "wire the already-`LOWERABLE`
`ENRICHMENT` node into the verb switch," but `PipelineEditable.lower` deliberately *ignores*
enrichment nodes (their truth is the companion `*_enrich.toon`, W4b) — compiling onto that node
could never round-trip through the flat file. The join therefore shipped as its own
`BuiltinNodeType.TRANSFORM_JOIN` node kind with flat home `processing.join {reference, on}`
(`reference` = a `reference/<id>` registry ref — a `produces: reference` pipeline, the
`EnrichmentConfig.Reference.ref` variant — or a verbatim path; `on` columns validated against
`declaredColumns`). The `enrichment` node stays companion-persisted and ignored by lower; the two
retire together at the Phase-6 migration. A `transform` step carrying both `join` and `filter`
compiles to two chained nodes. **Same compile-only arming posture as route/summarize:**
`PipelineConfig.prepare()` refuses an `active` pipeline carrying `processing.join` — the join model
executes post-commit via `EnrichmentEngine`, never in the linear ingest path yet. `PipelineLift`
emits the node right after map (dedup/summarize downstream see the enriched row set);
`RecipeConverter` projects it back with the plural `references/` spelling. Tests:
`RecordDedupRouteConfigTest` (+3), `RecipeCompilerTest` (+2), `RecipeConverterTest` (fixture
extended), `NodeConfigNameContractTest` (+1 pinned-lowerable, +1 draft-path). Full reactor green.
**Phase 3 remaining: S3 + S4.**

#### Phase 3 S3 design spike 2026-08-06 — table-entry `collect` does NOT get the S1/S2 treatment

Before touching code, checked whether S3 could ship compile-only the way S1 (`summarize`) and S2
(`join`) did — add a config record + flat home + node kind, gated shut at `prepare()`. **It cannot,
for two independent reasons, and the slice should stay a documented gap rather than be forced into
that shape:**

1. **No real target shape to mirror.** S1/S2 each round-tripped against a runtime that already
   exists and already consumes that exact config (`MaterializeTask`'s measure params,
   `EnrichmentConfig.Reference`'s `ref` join). Table-entry collect has no such counterpart:
   `EnrichmentConfig.Reference` (`EnrichmentConfig.java:78`) is a read-only *lookup* joined against
   Stage-1 partitions, not a triggerable row source; `PipelineConfig.Reference`
   (`PipelineConfig.java:612`) is the *produce*-side opposite. Authoring `processing.collect_table
   {dataset: <id>}` now would be structure that corresponds to nothing executable anywhere in the
   system — worse than authoring-only, it would be fictional.
2. **`dirs.poll`/`dirs.database` are unconditionally required at parse time, not just at arming.**
   `PipelineConfigParser.java:129-130` (`require(dirs, "poll"/"database")`) plus `validateDirs`
   (`:785-803`) reject a config missing them — before `prepare()`'s arming gate is ever reached. Every
   other authoring-only section (`route`, `summarize`, `join`) parses fine on an inactive draft and is
   refused only at arming; a table-sourced draft would fail to load at all unless `dirs.poll`'s
   hard requirement is loosened first — a change to a load-bearing invariant every existing pipeline
   kind relies on, not a wiring job.

**What would actually need to exist first (the real S3 scope, deferred):** a Signal a Dataset write
publishes, and a `collect` variant a Pipeline can bind to it instead of `CollectorConnector` — i.e.
`EnrichJob`/`EnrichmentEngine`'s publish path becoming (or being joined by) something Pipeline-node
shaped. This is genuine new design, not a slicing choice, so it is left to the operator to schedule
explicitly rather than forced now. **S4 (fixture parity gate) can still proceed independently** —
it targets the S1/S2 verbs already shipped, not S3.

**P3 S4 SHIPPED 2026-08-06** — the parity gate, scoped honestly to what S1/S2 shipped: the new
verbs are compile-only (arming refused), so the original "identical outputs" wording cannot be
executed — what IS gated is **parity of representation against the real artifacts** the verbs claim
compatibility with. Three legs: (1) `orders_enriched_rollup_pipeline.toon` (inactive draft carrying
dedup + join + summarize over the real orders schema) joined the walked `spaces/` corpus, so
`RecipeConverterTest`'s every-fixture round trip now covers the new sections over a real on-disk
file permanently (7 fixtures); (2) `RecipeVerbParityTest.everyRealEnrichmentReferenceIsExpressibleAsATransformJoin`
walks every real `*_enrich.toon`, loads it through `EnrichmentConfig.fromMap`, and asserts each
`references` entry (path and `ref` variants) compiles through the join verb with the source spelling
carried verbatim — the D-4 claim, gated over the corpus; (3)
`theDraftFixturesSummarizeMeasuresSpeakTheMaterializeGrammar` splits the fixture's `measures` by
`MaterializeTask.compileSpec`'s documented `count | agg(field)` contract and compiles them through
`MeasureCompiler` — the S1 byte-compatibility claim, pinned (if the grammar drifts, this fails).
Full reactor green, incl. every boot/registry scan over the new fixture. **The execution half of
the original S4 gate (enrich + materialize actually running as recipes with identical outputs)
lands with S3's executor machinery — it is S3-blocked, not forgotten.**
⚠ **Superseded 2026-08-28:** that execution half SHIPPED as part of **S3d**
(`RecipeExecutionParityTest`), and grounding refuted its stated blocker — the verbs were no longer
compile-only, because the A5 at-rest path had been executing them since 2026-08-11. **Phase 3 status:
S1/S2/S3 (a–d)/S4 ALL SHIPPED — the phase is COMPLETE.**

#### Phase 3 S3 DESIGN 2026-08-06 — table-entry `collect` via a Dataset-write Signal (design of record, pre-implementation)

Grounding (2026-08-06, full pass over the Signal/trigger/write machinery) established four facts the
design must respect:

1. **Three parallel commit mechanisms exist, all pipeline-shaped, none Dataset-shaped.**
   (a) `PipelineScheduler.onUpstreamCommit` (`PipelineScheduler.java:389`) fires downstream
   pipelines whose entry trigger is `{type: event, on: commit, from: flows/<id>}`
   (`PipelineTrigger.of`, `PipelineTrigger.java:60-97`, with `coalesce:`); (b)
   `JobService.mirrorPipelineCommit` (`JobService.java:519-529`) mirrors every terminal
   `BatchEvent` as a `pipeline.commit` Signal (payload `{pipeline, batchId, status, rows, ms,
   parts}`) consumed by `on_signal:` jobs; (c) `PipelineBatchSignal.emit`
   (`PipelineBatchSignal.java:29-53`) adds the observability `pipeline.batch.committed|failed`.
   None carries a Dataset id or database path.
2. **No Dataset-write Signal exists.** The write sites are `MaterializeTask.run:109-115`
   (stage-and-swap, then `store.write("dataset", target, …)` — no publish) and
   `ConsignmentProcessJobType.persistSummaries:178-198` (`ConsignmentOutputStores.record`, no
   publish). Nothing is stubbed for it.
3. **`EnrichJob` is the template for a non-file entry** (`EnrichJob.java:20-60`): it runs an
   engine over at-rest data and then publishes a plain
   `BatchEvent(job.name(), runId, "SUCCESS", …)` — fabricating pipeline-shape so the whole
   commit fabric (triggers, mirror Signal, coalescers) works unchanged.
4. **`dirs.poll`/`dirs.database` are load-bearing four ways** (the spike's blocker, now sized):
   the two `require()`s (`PipelineConfigParser.java:128-130`); `validateDirs:784-796` fences
   every other dir against `pollDir`; `errors`/`quarantine` **default off `pollDir`**
   (`:133-136`); and `MarkerManager` markers are file-paths-relative-to-poll
   (`MarkerManager.java:16,66`) — a table-entry pipeline has no file to mark, so its
   "already processed" concept must be a **watermark**, not a marker.

**Design of record (recommendation; operator sign-off before implementation):**

- **New Signal `dataset.write`** — published at the moment a Dataset's data becomes visible
  (post-swap in `MaterializeTask`, post-`record` in `persistSummaries`, and any future
  Dataset-producing sink). Payload `{dataset, rows, at, producer}` (`producer` = pipeline/job
  name). Additive, mirrors the `PipelineBatchSignal` posture — never replaces the pipeline-shaped
  signals.
- **Trigger form** — `PipelineTrigger` gains `{type: event, on: dataset, from: datasets/<id>,
  coalesce: …}`, resolved by the same scheduler path as `onUpstreamCommit` (a
  `onDatasetWrite` sibling subscribing to the new Signal). Recipe spelling:
  `collect: {dataset: datasets/<id>}` with the trigger riding the entry step as today (§2.7).
- **Runtime** — a `TableCollectRunner` in the `EnrichJob` mold: reads the bound
  `DatasetRelation` (watermark-filtered), feeds the existing transform/sink chain, and publishes
  a normal `BatchEvent` on commit so everything downstream (mirror Signal, counters, Stage C)
  works unchanged. Consignment = one watermark window over the Dataset.
- **Watermark, not marker** — per (pipeline, dataset) high-water state (last consumed
  `materialized.at` / row fingerprint) persisted in the ledger DB (the `AcquisitionLedger`
  precedent), replacing `MarkerManager` for this entry kind. Semantics v1: whole-Dataset
  re-read on change is the honest default; incremental (partition/row watermark) is a declared
  fast-follow — never faked.
- **Parser loosening, fenced** — `dirs.poll` becomes conditionally required: mandatory unless the
  entry is dataset-shaped; `dirs.database` stays mandatory always (the sink side).
  `validateDirs` skips the poll fence when poll is absent; `errors`/`quarantine` then default
  off `databaseDir` instead. Every existing pipeline kind is untouched (poll still required for
  them — the invariant narrows, it does not vanish).

**Implementation slices (each shippable, in order):** **S3a ✅ SHIPPED 2026-08-28** — the
`dataset.write` Signal: `com.gamma.signal.DatasetWriteSignal` (mirrors `PipelineBatchSignal`'s
posture — ambient `EventLog.current()`, never breaks the write it announces), payload
`{dataset, rows, at, producer}`, published post-swap in `MaterializeTask.run` and
post-`record` in `ConsignmentProcessJobType.persistSummaries` (one signal per distinct
`tableName`, rows summed). `DatasetWriteSignal.TYPE` is the constant S3b's scheduler
subscription matches on. `DatasetWriteSignalTest` (2 tests: queryable payload + subject ref;
`-1` rows / absent producer never put a null in the payload). Reactor 3663/0/0/5. · **S3b ✅ SHIPPED 2026-08-28** — the trigger form. ⚠ `PipelineTrigger`
needed NO change (its `on`/`from` were already generic); the work was scheduler-side:
`PipelineScheduler.onDatasetWrite` (exact sibling of `onUpstreamCommit` — same off-thread
coalescer hand-off) matches `{type: event, on: dataset, from: datasets/<id>}`, wired via the
space `EventLog` subscriber in `CollectorService.start()`. 🔴 A namespace FENCE was required in
`onUpstreamCommit`: `triggerMatches`' suffix rule cannot tell `datasets/orders_rollup` from a
PIPELINE named `orders_rollup` committing, so `on: dataset` triggers are now skipped there —
pinned by `datasetTriggerFiresOnDatasetWriteAndNotOnALikeNamedPipelineCommit` (a decoy pipeline
commit must NOT fire; the Signal must, end-to-end through a real CollectorService).
Reactor 3664/0/0/5. ·
**S3c — DESIGN AMENDED 2026-08-28 (operator decision, in-session): file-shaped consumption, no
new runtime.** The runner/watermark half of the 2026-08-06 design is SUPERSEDED. Rationale, from
grounding the alternative the operator proposed ("use an S3 endpoint like an inbox"): parquet is
only missing an INGEST lane (fully supported on output); `MaterializeTask` snapshots are
timestamp-named (`matrix-<millis>.parquet`) so the EXISTING marker dedup gives correct
re-ingest-on-refresh semantics with no watermark; `LocalFileSystemConnector`/`S3Connector`
already exist. Amended shape, two legs: **S3c-1 ✅ SHIPPED 2026-08-28** — the `parquet` ingest lane: `PipelineConfig.Parquet` +
`frontend: parquet` + `DuckDbCsvIngester.buildParquetReadSpec` (per-column `CAST(sel AS VARCHAR)`
projection — PROBED: `read_parquet` returns the file's real types and has NO `all_varchar` option)
+ the served catalog Builtin + binary preview (`ComponentPreview.parsingParquet`). One honored
option, `hive_partitioning` (probed: exposes `year=/month=/day=` dir levels as columns, and works
on a SINGLE file path, not only a glob); every other key refuses by name. No extension load, no
Compression wrap. ⚠ Two contract guards fired during the build and were RIGHT both times:
`ParsersTest` refuses an empty grammar schema (resolved by declaring the honest option, not by
exempting), and `ControlApiParsersTest` pins the served catalog (now 8 entries, parquet at
index 3). `ParquetParsingTest` (5) incl. fail-closed missing-selector. Reactor 3669/0/0/5. ·
**S3c-2 ✅ SHIPPED 2026-08-28** — dataset-entry collect via a derived acquisition. As built:
`Collector.dataset` (typed field; `source.dataset` requires `connector: dataset` and vice versa,
both fail-closed) · the `dataset` connector scheme (`DatasetCollectorConnectorFactory`,
ServiceLoader-registered IN `inspecto-engine` — resolves the id → snapshot dir FRESH each build
through the same chain every Dataset reader uses: component `physicalRef` →
`DataRef.requireUnder(-Ddata.dir)` → `storeReadRoot`; registry off `-Dassist.write.root`, the
MaterializeTask ambient pattern; delegates discover/fetchTo to `LocalFileSystemConnector` over
the resolved dir; ⚠ `post` is FORCED to RETAIN — a consumer can never delete/move a producer's
snapshots, whatever its config says) · `RecipeCompiler.collect` compiles
`collect: {dataset: datasets/<id>}` → `connector: dataset` + stripped id, and `dataset:` +
`connection:` refuses MALFORMED_STEP ("one entry consumes one source"); ⚠ deliberately NO
blanket unknown-collect-key refusal — `RecipeConverter` legitimately round-trips collector keys
through `collect:` (the AUTHOR-1 regression shape) · `PipelineLift.acquisitionNode` carries the
key for the editable round-trip; the converter echoes it verbatim. The copy kills the
stale-delete race, keeps backup/quarantine/markers/retention intact (timestamped snapshot names
make marker dedup the refresh semantics — no watermark), and no parser fence was needed:
`dirs.poll` is the pipeline's own inbox. `on: dataset` (S3b) supplies event latency.
`isRemote()==true` for the scheme, so the B3b acquire cycle drives the copy. Reactor 3674/0/0/5.
⚠ Archive compression (.zip/.tar/.bz2) is Path-1 (file feeds) machinery and owes this path
nothing — Dataset consumption only ever reads product-written parquet. · **S3d ✅ SHIPPED
2026-08-28** — the projection polish + the deferred execution half of the P3 S4 parity gate.
Grounding first REFUTED the slice's own premise: join/summarize were NOT compile-only anymore —
the A5 at-rest path (2026-08-11) already executes them for real (`PipelineJobRunner` →
`PipelineExecutor` → `RowShaper`, gated on an authored `output_store:`), so S3d needed no new
runtime, only the gate and the polish. As built: (1) `RecipeConverter` now collapses the compiled
`connector: dataset` + `dataset: <id>` pair back to the authored `collect: {dataset:
datasets/<id>}` ref spelling (the `connection` treatment; conditional, so other connectors keep
the verbatim pass-through) — before this the projection leaked both raw keys and the round trip
was untested at the converter layer. (2) `RecipeExecutionParityTest` (com.gamma.job — MaintenanceJob
is package-private) runs each verb end-to-end through the REAL chain (compile → `ConfigCodec.toToon`
→ `PipelineConfig.load` → `PipelineJobRunner`) against the legacy runtime over the same rows:
`transform.join` vs `EnrichmentEngine`'s reference LEFT JOIN (unmatched key carries NULL, both
arms) and `summarize` vs `MaterializeTask` (same values under the same `MeasureCompiler` column
names, `sum_amount`/`count`). ⚠ Scope honesty: parity is per VERB — a real `*_enrich.toon`'s
hand-authored transform SQL (custom names, `ROUND`) is not byte-reproducible by the closed verb
set and never was the claim. (3) The stale "compile-only" doc comments in
`RecipeCompiler`/`RecipeVerbParityTest` were corrected to the as-built truth. Reactor 3677/0/0/5.
**Phase 3 S3 is COMPLETE (S3a–S3d) — UI-S7's table-entry half is unblocked.**

#### Phases 3/6 user-surface scope SUPERSEDED 2026-08-06 — user-facing 'Job' un-banned (operator decision)

The v1.0 end-state "Jobs panes/routes and `*_job.toon` authoring migrate to table-entry Pipelines"
is withdrawn: **Job is again a first-class user-facing concept** (GLOSSARY §6-A entry + §13 row
updated; authoring contract in `superpower/job-parameter-contract-plan.md` §0-A). The S3a–d design
of record above is unaffected and proceeds as an *additive* thread — table-entry Pipelines
complement Jobs, they no longer replace them. Consequences: Phase 6's Jobs-UI retirement is
cancelled; the per-kind authoring migrations (D-4 enrichment, D-7 materialize) become S3-gated
options to re-decide when S3 lands, not commitments.

#### Phase 4 GROUNDED 2026-08-06 — findings that shape the slices

1. **The housekeeping keys are scattered but all real** — each Guarantee has an existing home and
   consumer: file dedup = `source.duplicate` (`PipelineConfig.java:359-383`, modes
   path/metadata/checksum/etag) consumed in `BatchProcessor.finalizeSource`; backup =
   `dirs.backup` + inline `BatchProcessor.backupFile` (`:267-281` — distinct from the job-level
   `BackupTask`, a different concern); quarantine = `dirs.quarantine` + `QuarantineManager`;
   markers = `dirs.markers` + `MarkerManager`; gap watch = `source.gap_detection`
   (`:342-357`) + `GapDetector`/`GapTracker` (inspecto-acquire); retention =
   `processing.retention_days` (`:72`) + `MarkerManager.java:118-120`. `source.guarantee`
   (`:316-340`) is the delivery-class enum, a separate concept the fold must not conflate.
2. **`RecipeCompiler` already reserves the block**: non-empty `guarantees:` refused with
   `GUARANTEES_NOT_LOWERABLE` (`RecipeCompiler.java:68-70`) — the fold replaces that refusal for
   known keys only; unknown keys stay refused loudly.
3. **Stage progression exists as *code ordering*, not durable state.** `BatchProcessor.finalizeSource`
   (`:89-220`) documents the crash-safe commit order (register → manifest → backup → markers LAST →
   ledger), but `BatchManifest.MemberEntry.status` is one flat terminal string and `StatusStore`
   (`StatusStore.java:21-45`, impls `FileStatusStore`/`DbStatusStore`) is read-side derivation
   only. Phase 4(b) formalizes the ordering the code already enforces into recorded per-file stages.
4. **Premise correction — per-node conservation counters already exist** in the executor lane:
   `PipelineExecutor.ProvenanceCollector` (`:59-70,226`) fires per `(nodeId, rel, rowCount)`,
   persisted as `ProvenanceRow` (`PipelineJobRunner.java:171-184`), and `ConservationCheck.imbalances`
   (`ConservationCheck.java:46-71`) already flags LOSS/AMPLIFICATION as
   `PIPELINE_CONSERVATION_IMBALANCE` events (`PipelineJobRunner.java:239-259`). §11.3's deliverable
   is the **legacy-lane gap**: `BatchIngestStrategy.writeAndTrace` only *logs* dedup drops (`:171`)
   and counts nothing else — plus edge-grain (diverted per reject stream) on the substrate.
5. **Premise correction — `PipelineNode.enabled()` already exists but with the WRONG semantics for
   D-13**: today it is a pure in-memory bypass (`PipelineExecutor.java:135,187` — `continue`,
   "NiFi stopped processor produces nothing"), carried through lift/lower by `PipelineEditable`
   (`:170,335,445`). D-13 requires halt-and-**park** (durable, manifest `parked_at`, drain on
   re-enable) — a semantics change on the same flag, not a new one. `RecipeCompiler` does not yet
   compile a per-Step `enabled:`.
6. **The plan's "split acquire/ingest timers" half-pause is a docs simplification** — no such
   scheduler class exists; the closest primitives are `CollectorProcessor`'s poll loop +
   `StabilityGate` + the high-watermark filter (`CollectorProcessor.java:451-475`), all gating
   *discovery*, not mid-graph Steps. The durable "inbox" is `dirs.poll` itself; drain = next poll.

**Slices of record (each shippable):** **P4 S1** the Guarantees fold — recipe `guarantees:`
{`file_dedup`, `backup`, `quarantine`, `markers`, `gap_watch`, `retention`} compiles onto the
existing homes above (executable today — no arming gate needed, these consumers are live) +
converter projection + round-trip over the fixture corpus; unknown keys keep the named refusal ·
**P4 S2** per-file stage progression — durable stage records through `finalizeSource`'s documented
ordering + "where is file X" answerable via `StatusStore`/API · **P4 S3** counters — persist the
legacy lane's dedup/filter/quarantine counts and extend the provenance substrate to
`recordsIn/recordsOut/diverted` edge grain; conservation gate green over a fixture · **P4 S4**
per-Step `enabled:` park/drain (D-13) — flip executor bypass to halt-at-boundary, manifest
`parked_at` accretion, drain on re-enable, recipe compiles per-Step `enabled:`; gated by the S2
stage progression (per §2.7 cost 3). **IN FLIGHT 2026-08-28: S4-pre/S4a/S4b shipped, S4c/S4d remain
— [`elt-s4-park-drain-plan.md`](../archived-documents/plans-archive/elt-s4-park-drain-plan.md).**

**P4 S1 SHIPPED 2026-08-06** — the Guarantees fold: `RecipeCompiler` compiles a top-level
`guarantees:` map ({`file_dedup`, `gap_watch`, `markers`, `quarantine`, `retention`}) onto the live
housekeeping homes named in the grounding (finding 1) — **no arming gate**, these consumers
(`BatchProcessor`, `MarkerManager`, `QuarantineManager`, `GapDetector`) already run today, so a
compiled recipe is immediately executable, unlike Phase 3's compile-only verbs. Applied as a
post-`lower` overlay (`applyGuarantees`) because lower's ownership rule would otherwise clear these
sections when no owning node models them. `file_dedup: fingerprint` is the recipe spelling of
`source.duplicate.mode: checksum`; `file_dedup: marker` refuses toward the `markers:` guarantee
(one-word-one-concept: marker housekeeping is not a dedup mode). `backup` stays OUT of
`guarantees:` by design — it is the sink step's own key (`sink: {backup: …}`) — and refuses with a
named code pointing there if declared as a guarantee. Unknown guarantee keys refuse loudly
(`GUARANTEES_NOT_LOWERABLE`, the same code the old blanket refusal used, now precise). Converter
projects the same five keys back under `guarantees:` (never onto a step), completing the round
trip. Tests: `RecipeCompilerTest` (+3: fold, backup-refuses, marker-refuses),
`RecipeConverterTest` (+1 projection test, fixture extended with duplicate/gap_detection/markers/
quarantine/retention). Full reactor green (200+ tests, 0 failures). **Phase 4 remaining: S2
(per-file stage progression), S3 (counters), S4 (per-Step enabled park/drain).**

**P4 S2 SHIPPED 2026-08-06** — per-file stage progression: `BatchProcessor.finalizeSource`'s
documented crash-safe commit ordering (register → manifest → backup → markers LAST →
ledger/watermark, finding 3) is now durable, queryable state, not just code sequencing. New
`FileStage` enum (`REGISTERED, MANIFESTED, OUTPUT_REGISTERED, BACKED_UP, MARKED,
WATERMARK_ADVANCED` — only the boundaries the method genuinely crosses, none invented ahead of the
code that would report them) + `DbFileStageStore`/`FileStages` (`com.gamma.consignment`), an
**insert-only** registry mirroring `DbConsignmentOutputStore`/`ConsignmentOutputStores` exactly:
same JDBC-over-DuckDB shape, same default-off contract (`-Dfile.stages.backend`), same fail-open
best-effort `record()`. Wired into `finalizeSource` at all six boundaries, keyed by
`(sourceId, relativePath)` — the same key `AcquisitionLedger` uses. Full production wiring, not a
stub: `SpaceRoot.fileStagesDbUrl()` + `ServiceStores.openFileStageStore` +
`CollectorService`/`SpaceManager` register/unregister, mirroring the output-registry's wiring
line for line. **"Where is file X" is answerable today**: `GET
/runs/{name}/files/stage?path=<relative>` returns the recorded progression (empty, not an error,
when the registry is default-off — the same degraded-but-correct contract as every other
optional store). Tests: `DbFileStageStoreTest` (store unit tests, mirroring
`DbConsignmentOutputStoreTest`), `FileStageRegistrationTest` (integration — asserts the recorded
order matches `finalizeSource`'s own ordering comment, and that commit succeeds unaffected when
the registry is off), `ControlApiTest` (+1, the route's required-param + default-off-degrades-empty
contract). `docs/okf/backend/engine/db-layer.md` gained §3.10 + the file-topology/Postgres-flag
rows. Full reactor green (666 tests in inspecto-engine, 200 in inspecto, 0 failures). **Phase 4
remaining: S3 (edge-grain counters — legacy-lane dedup/filter/quarantine counts + provenance
extension), S4 (per-Step `enabled:` park/drain, D-13) — S4 is gated on this slice per §2.7 cost 3.**

**P4 S3 SHIPPED 2026-08-06** — edge-grain counters, in two parts per the grounding's own split
between "the substrate already exists" and "the legacy lane genuinely has none":
1. **Executor lane**: `ConservationCheck` gained `RelCount` + `relCounts(counts)` — the per-`(node,
   rel)` breakdown underneath `imbalances()`'s existing per-node aggregation, tagging each count
   `diverted` when its `rel` is a §2.6 reject stream (`dropped`/`invalid`/`duplicate`/`unmatched`)
   rather than the main trunk or a named `route:*` branch (ordinary content-routing, not
   diversion). Pure and additive — `imbalances()`'s contract is untouched (existing tests still
   pin it). The provenance substrate (`ProvenanceCollector`/`ProvenanceRow`) already carried this
   at `(node,rel)` grain; the finding was that nothing exposed it as such — `relCounts` is that
   exposure, for a Pipeline Document or per-file drill-down to show *which* edge carried the
   diverted rows.
2. **Legacy lane**: `BatchIngestStrategy.applyRecordDedup` (the CSV-ingest dedup QUALIFY) only
   logged its drop count via SLF4J — the method's own comment flagged this as deferred Phase-4
   work. It has no `PipelineGraph`/provenance context to record against (confirmed: no per-node
   graph exists in this lane), so persisting durably needed a different seam than
   `DbProvenanceStore`. New `EventType.DEDUP_RECORDS_DROPPED` (mirrors `SEQUENCE_GAP`/
   `PIPELINE_CONSERVATION_IMBALANCE`'s own idiom — `EventLog.current()`, correctly MDC-routed to
   the calling space with no new plumbing) carries `keys`/`dropped` attrs + the batch id as
   `correlationId`; already queryable via the existing `GET /events/search?type=` route — no new
   API. Filter/quarantine counts stay ungrounded, confirmed genuinely absent (no `where` clause
   exists anywhere in the legacy lane at all, and quarantine is file-grain only) — nothing to
   persist because nothing is computed; not a gap this slice manufactures work to fill. Tests:
   `ConservationCheckTest` (+1), `RecordDedupExecutionTest` (+1, asserting the durable event).
   Full reactor green.

**Phase 4 S4 — per-Step `enabled:` park/drain: DESIGN SPIKE 2026-08-06, DEFERRED (mirrors the
Phase 3 S3 pattern — a documented gap, not a forced slice).** Checked whether even compile-only
authoring support (`RecipeCompiler` accepting a Step-level `enabled: false`) could ship the way
Phase 3's S1/S2 did. It cannot, for three independent reasons, each confirmed by direct grounding:

1. **The full park/drain semantics D-13 promises do not exist in any form.** `PipelineNode.
   enabled()` (`PipelineNode.java:63-74`) is a pure in-memory bypass — `PipelineExecutor.java:135,
   187` `continue`s past a disabled node, so it (and anything reachable only through it) simply
   never enters `produced`; a disabled sink never enters `sinkInputs`, so its branch is never
   committed at all (`PipelineExecutorTest.disabledSinkIsNotCommittedAsABranch` pins exactly this
   skip-and-vanish contract). There is no `PARKED` state (confirmed absent from the Phase-4-S2
   `FileStage` enum this slice would need to extend), no store for it, and no drain/resume
   scheduler that would re-enter execution from a parked boundary when the Step re-enables.
2. **`BatchGraphRunner` — the only thing that could execute a branch-aware chain at all — has zero
   production call sites**, confirmed by grep across `BatchProcessor.java`: test-only today, the
   identical status Phase 2 S5 recorded for `route`'s arming gate. Building real park/drain
   execution semantics onto a lane nothing runs in production would be building on top of dead
   code, not dead config — a worse version of the W1 trap.
3. **The flat `*_pipeline.toon` format — `RecipeCompiler`'s own compile target — has no home for a
   per-node `enabled` flag at all**, unlike every other Phase 2/3 verb. Tracing every branch of
   `PipelineEditable.lower` (the flat-file lowering RecipeCompiler delegates to) confirms none of
   them preserve an arbitrary `enabled` config key: the parser branch copies only
   `PARSER_OWNED` keys, `TRANSFORM_DEDUP`/`TRANSFORM_JOIN`/`TRANSFORM_SUMMARIZE` each emit a fixed,
   enumerated key set (`processing.dedup {keys, order_by}`, etc.), and the primary-sink branch
   copies only its own enumerated write-tuning keys. `enabled` is a graph-editor-model concept
   (`PipelineEditable.toMap`/the canvas JSON), never a flat-file one. Compiling `enabled: false`
   from a recipe would therefore compile successfully and then **silently vanish on the very next
   lower** — the exact dead-config failure mode S1/S2's arming gates exist to prevent, except here
   there is no config shape to gate *inactive*, because the shape itself doesn't survive a save.

**What would actually need to exist first (the real S4 scope, deferred):** a durable `PARKED`
state (extending `FileStage` or a sibling concept) + a scratch-materialization convention at the
disabled boundary (route's `RowShaper` already materializes one table per branch on every save,
so the *mechanism* to reuse exists — nothing currently marks a table's origin as a pause point) +
a drain/resume scheduler that re-enters `PipelineExecutor` from the parked boundary + a flat-file
(or graph-native) home for the flag that actually survives a save + `BatchGraphRunner` acquiring a
real production call site in the first place, since none of the above matters while nothing runs
it. This is new design across four different subsystems, not a slicing choice — left for the
operator to schedule explicitly, per the same posture as the deferred Phase 3 S3 (Dataset-write
Signal). ~~**Phase 4 status: S1/S2/S3 shipped; S4 deferred (documented gap above).**~~

> 🔴 **SUPERSEDED 2026-08-28 — the spike's three reasons are ALL now stale, and S4 is IN FLIGHT.**
> Read this and the "RE-GROUNDED" subsection below, never the 2026-08-10 findings alone. Reason (2)
> died when the executor armed in production (2026-08-26); reason (1)'s park semantics and reason
> (3)'s flat-file home were BUILT by the slices below. **Phase 4 status: S1/S2/S3 shipped; S4-pre +
> S4a + S4b SHIPPED 2026-08-28 (`cb12032d`, `9873ebfe`, `575c9912`); S4c (drain) and S4d (canvas
> toggle) remain** — slice plan, refusals and as-built facts:
> [`elt-s4-park-drain-plan.md`](../archived-documents/plans-archive/elt-s4-park-drain-plan.md). The flat-file home is
> `processing.disabled_steps` (one id list, lift-overlaid, lower-derived); park writes the manifest's
> `parkedAt`/`parkedTables` with the rows durable as Parquet under the park home.

#### The branch-aware lane is BLOCKED on output parity, not merely unscheduled (grounded 2026-08-10)

S4's finding 2 above says `BatchGraphRunner` has no production call site. Asked directly how big
wiring one is — because it gates S4 *and* Phase 6 — the answer is that **it is not a dispatch, it is
an unscoped parity project.** Three findings, each read off the code:

1. **The graph is constructible but not executable as lifted.** `PipelineLift.lift(cfg)` produces one
   from any `PipelineConfig`, but the parser's `csv` settings stay on the parse node while
   `RowShaper.columnsOf` reads them off the **map** node, so the map node throws. `PipelineDryRun.
   withMappingContext` exists purely to patch this and says so: *"Without this, a dry-run of any
   registered pipeline with a schema fails on its map node."* That rewrite lives only in the
   dry-run/editor path; production would need its own.
2. **`branchCommitLog` has no home.** `BatchGraphRunner.Input` wants a durable `Path`; there is no
   config key, convention, or retention policy for it in the `BatchProcessor` lane, and the log is
   append-only with nothing addressing growth. Both test harnesses pick a `@TempDir` path.
3. ⚠ **The two lanes are independent implementations of the same concepts.** `writeAndTrace` applies
   `DecisionRuleApplier` routing, record dedup, reference-version stamping, multi-destination fan-out,
   and per-file `EventTimeBounds`. No code path connects `RowShaper`/`PipelineExecutor` to the first
   three. So flipping `engages(g)` does not reroute a write — it substitutes a pipeline whose **output
   parity with the current one is unknown**, over real operator data.

⇒ **Reclassify: Phase 4 S4 and Phase 6 are BLOCKED, not deferred.** S4 cannot be built on a lane
nothing runs (its own finding 2), and Phase 6 cannot delete the legacy path until the replacement is
proven equivalent. The prerequisite for both is a **parity scope** — decide whether routing, dedup and
reference versioning get graph-node equivalents or whether the gap is accepted — and that is a design
question, not a slice. ⛔ Do not "just wire `engages()`": the scalar half of `Input` is trivial, which
makes this look far smaller than it is.

#### Parity scope, RE-GROUNDED 2026-08-28 — the three findings above are now largely STALE

The branch-aware executor **shipped and armed in production 2026-08-26** (`route:` pipelines divert
inside `BatchIngestStrategy.writeAndTrace:132-137` → `graphWriteAndTrace`), which resolves or
reframes each 2026-08-10 finding. Read this section INSTEAD of re-deriving from the three above:

1. **The map-node `csv` gap is MOOT for the shipped lane.** Production seeds the executor **at the
   route node's upstream data edge** (`graphWriteAndTrace:235-246`, comment: *"the executor never
   re-runs parse/map — it walks route → sinks only"*), so the map node is never executed at rest and
   `withMappingContext` rightly stays dry-run-only. It becomes a real prerequisite ONLY if the lane
   is ever extended to execute parse/map at rest — in that change, move the rewrite into
   `PipelineLift` itself, not a second copy.
2. **`branchCommitLog` HAS a durable home**: `dirs.temp()/branch_commit_<batchId>.log`
   (`graphWriteAndTrace:250-252`), fsync-per-record (`BranchCommitLog`). The one remainder is
   **growth**: nothing deletes the per-batch files (verified — no delete anywhere in the class or
   its callers). Zero-code housekeeping exists today: a `cleanup` maintenance job on `dirs.temp`
   with `glob: branch_commit_*.log` + `retention_days`. A delete-on-successful-commit in
   `BatchProcessor.commit`'s tail is the code option (small) if temp growth ever matters.
3. **Dedup parity is SHIPPED and BETTER than legacy** (2026-08-11 operator decision): legacy
   in-line dedup was deleted; `processing.dedup` lifts to a `transform.dedup` node and
   `RowShaper.dedup` emits losers as an inspectable `duplicate` relation (legacy only logged a
   count). Not a gap. (Side note of record: `EventType.DEDUP_RECORDS_DROPPED` currently has no
   emitter — kept deliberately for the Stage-2 executor.)
4. **Decision-Rule routing and reference-versioning are LOUD REFUSALS, not silent divergence**:
   `graphWriteAndTrace:226-233` runs `DecisionRuleApplier` pre-graph and throws when rule-routed
   outputs combine with route branches (mirroring the flat path's rule-routing+fan-out refusal),
   and throws on a versioned reference store per branch (*"one version history is ill-defined
   across branches"* — the same rule as `sinks:>1` at prepare()). Graph equivalents would be
   LARGE (a rule node carrying quarantine side effects + per-rule tagging; a stateful versioning
   node whose cross-branch semantics are ill-defined — plausibly a permanent refusal, not a
   feature).

**✅ DECIDED 2026-08-28 (operator): the two refusals in (4) are PERMANENT POSTURE.** Decision-Rule
routing and versioned reference stores are refused by name when combined with route branches —
that is the contract, not a gap; do not re-file either as work without a demand case that names a
real pipeline. **⇒ Phase 4 S4 and Phase 6 are UNBLOCKED**, with two preconditions carried into
their slices:
- **S4 precondition (small):** commit-log housekeeping — today a `cleanup` maintenance job on
  `dirs.temp` with `glob: branch_commit_*.log`; a delete-on-successful-commit in
  `BatchProcessor.commit`'s tail is the code option if temp growth matters.
- **Phase 6 precondition (real work, scoped in its slice):** deleting the legacy lane needs the
  graph lane to carry NON-route pipelines with proven output parity (an output-comparison test of
  the two lanes on the same non-route pipeline) — and that extension re-opens item (1): move
  `withMappingContext` into `PipelineLift` in the same change.

#### Phase 6 precondition, GROUNDED 2026-08-29 — three premises corrected

Read before scoping the slice; the paragraph above is directionally right and factually wrong in
three places.

1. **The engagement gate is not where the plan implies.** `BatchGraphRunner.engages(g)` is
   `dataFedSinkCount(g) > 1` (`BatchGraphRunner:123`) — it is NOT route-specific. The route gate is
   the caller's: `BatchIngestStrategy:132` forks only `if (cfg.routeConfig() != null)`. A non-route
   pipeline is therefore excluded **twice**: by that caller gate, and because its lift has exactly
   one data branch, so `engages()` would be false anyway. ⚠ A `sinks:[N]` fan-out is deliberately
   ONE branch with N destinations — admitting non-route pipelines by loosening the count would
   divert fan-out into a lane that does not implement it.
2. **`withMappingContext` is dry-run-only, and nothing silently loses it today.** It exists solely
   in `PipelineDryRun` (`:96,:168`) — no production ingest path calls it. The reason is that the
   graph lane never executes a `transform.map` node at all: `graphWriteAndTrace` seeds
   `PipelineExecutor` at the route node's UPSTREAM data edge (`:238-246`), i.e. over a table the
   flat `CsvIngester` has already parsed and mapped. So item (1) is real but for a different
   reason than stated: it only bites once the graph lane runs parse→map itself, when `RowShaper`
   would execute a schema-carrying map node in production for the first time.
3. **"Carry non-route pipelines" is a lane migration, not a gate flip.** Beyond the seed (which
   hard-requires a route node — `orElseThrow` at `:240`), two capabilities are flat-lane-ONLY and
   are today *refused* rather than implemented in the graph lane: multi-destination `sinks:`
   fan-out, and the versioned reference-store write (`stampReferenceVersions`). Either one appearing
   in a non-route pipeline means real new work, not a parity assertion.

**No two-lane comparison test exists.** `RouteIngestEndToEndTest` is single-lane (graph only); its
"finalisation parity" is prose, not a side-by-side diff. The parity gate has to be written, and it
cannot be written before the graph lane can actually run a non-route pipeline — so it is the slice's
*exit* criterion, not its entry point.

#### Phase 6 slice A — the narrow admission, SHIPPED 2026-08-29

Scope taken: admit a non-route pipeline to the graph lane **only where the two lanes are provably the
same write**, and prove it with the side-by-side diff. Everything else stays flat, refused by name.

`BatchIngestStrategy.graphLaneCarries(cfg)` admits when ALL hold: one destination (`sinks:>1` is a
fan-out the graph lane does not implement — and `dataFedSinkCount` counts N plain-data sinks as ONE
branch, so engagement could never separate them); no versioned reference store
(`stampReferenceVersions` is flat-lane-only); and the single sink is fed straight off the `map` node,
so the walk performs only the write. `writeAndTrace`'s seed generalised from "the route node's
upstream" to `seedFeedingTheWrite` — "the node whose data relation IS the materialised table" —
which is the same invariant in both lanes. Gate: `FlatVsGraphLaneParityTest` runs one materialised
table through both lanes and diffs output files, partitions, ROWS ON DISK, the lineage matrix and the
event-time bounds; nine existing `writeAndTrace` tests now pass production's value, so they became
parity evidence too.

Two hazards found while building it, neither in the plan:

1. 🔴 **Two of `writeAndTrace`'s four callers write a batch in SEVERAL calls** (one per chunk, one per
   segment), and the graph lane's `BranchCommitLog` is keyed by batchId — calls 2..N would be skipped
   as "already committed" and their rows would vanish. The admission is therefore a **caller's**
   decision (`wholeBatchWrite`), not a config property. The route lane is only *incidentally* immune
   (a chunked batch is single-member + single-destination; a segmented one is multi-schema, which
   route refuses) — the new admission must not inherit that luck by accident.
2. 🔴 **Decision rules are a space-registry fact, not a config property**, so the admission cannot see
   them statically — and the graph lane refuses rule-routed outputs. `DecisionRuleApplier.apply` is
   now hoisted **above** the fork (both lanes ran it as their first act anyway, so this also removes a
   duplicated call), and its RESULT is part of the admission: a rule that actually routed rows keeps
   the pipeline flat. Caught by `DecisionRuleWiringTest`, which the first cut broke.

#### Phase 6 slice B — multi-destination fan-out, SHIPPED 2026-08-29

Grounding refuted the cost here too: **the fan-out needed no new machinery.** The lift already emits one
`sink.persistent` node per `sinks[]` destination, each fed by its own `data` edge off map, and
`PipelineExecutor` already writes every data-fed sink independently — so admitting `sinks:>1` was a
one-line relaxation plus the parity proof, and it GAINS per-destination crash resumption (one `BRANCH`
row each) that the flat lane's single write loop does not have.

⚠ The thing to keep straight: `dataFedSinkCount` still counts those N sinks as ONE branch. That is an
ENGAGEMENT question for the ROUTE lane ("is there a second branch worth diverting for?") and must not
be read as a claim about whether the graph lane can perform the write.

`FlatVsGraphLaneParityTest.bothLanesFanOutTheSameRowsToEveryDestination` diffs both destinations' rows
across both lanes.

#### Phase 6 slice C1 — several writes per batch, SHIPPED 2026-08-29

Slice A's hazard 1, closed. The chunked and segmented ingest paths call the write seam once per chunk /
per segment, reusing the same sink node ids against ONE shared branch ledger — so a second call read as
"already committed" and its rows would have vanished. Fixed by scoping the LEDGER KEY, not the log:
`BranchCommitCoordinator` takes a **write scope** and records `<scope>::<branch>`, while
`branchCommit` still receives the bare branch id (it is a graph node the caller must write). The
whole-batch callers pass `""` and record exactly the keys they always did — which is what keeps the
drain, reading bare sink ids back out of the log, and `BatchProcessor.commit`'s single-log cleanup
untouched. ⚠ Deliberately NOT a per-write log FILE: the batch's log path is spelled by batchId in three
places (create, drain-resume, cleanup), and multiplying it would have leaked segment logs in `temp`.

The four callers now say what they are: `CsvBatchStrategy` and union streaming pass `""`, the chunked
path passes its chunk base name, the segmented path its segment key. Pinned by
`FlatVsGraphLaneParityTest.severalWritesInOneBatchEachLandWhenTheyCarryTheirOwnScope` — distinct scopes
BOTH write, a repeated scope is still skipped (that replay is what protects a crash-resumed batch).

#### Phase 6 slice C2 — the versioned reference store, SHIPPED 2026-08-29

Its refusal in the graph lane was always **route-specific** — *"one version history is ill-defined across
branches"* — and a non-route pipeline has no branches, so the stamp simply runs before the walk exactly
as it does on the flat path: system columns appended, within-batch key duplicates folded out, and the
**batch-unique file stem** that makes the write an append instead of an overwrite of the prior version.
The route combination stays refused by name (permanent posture, 2026-08-28). Pinned by
`FlatVsGraphLaneParityTest.bothLanesStampAndAppendAVersionedReferenceStore`, which compares the stem as
well as the rows — the stem IS the append semantics.

🔴 **It also uncovered two defects around `dirs.temp`, which is OPTIONAL.** First a latent NPE, live
since the branch-aware executor shipped: the branch commit log read `cfg.dirs().temp()` directly, so ANY
pipeline omitting it would have thrown on the graph lane — a config the flat lane accepts, because the
flat lane keeps no commit log at all. The path now resolves through ONE helper
(`BatchIngestStrategy.branchCommitLogPath`) shared by all three sites that spell it — create,
drain-resume, cleanup — which is also the invariant that keeps a drain looking where the park run wrote.

Then the fallback itself: a JVM-temp-dir default puts a **durable** ledger somewhere shared and unswept,
and it is not per-pipeline. A stale `branch_commit_<batchId>.log` left in `%TEMP%` by an earlier run made
the coordinator skip the branch as "already committed" and the batch wrote NOTHING — which is exactly how
it presented: a parity case that passed once and then failed identically forever after, looking like
flakiness. **So `graphLaneCarries` now refuses a pipeline with no configured scratch dir**: the flat lane
needs no ledger, so it is the correct lane for that config. The JVM-temp fallback survives only for the
route lane, where it beats an NPE, and is documented as a last resort.

**Still flat, and still to do before the lane can be deleted:** anything with a node between map and
sink (`dedup`/`join`/`summarize`, all of which `prepare()` still refuses for this lane anyway). Only once those are carried does item (1) — moving `withMappingContext` into
`PipelineLift` — come due, and only because the graph would then execute the map node itself.

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
