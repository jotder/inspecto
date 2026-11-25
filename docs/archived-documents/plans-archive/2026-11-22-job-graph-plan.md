# Job graph — a Job's chain is its own Pipeline, drawn from store inputs to a store, joined to the rest in Topology

**State:** ✅ **DONE 2026-11-25 — JG1 (`ca6bf0256`) and JG0, JG2–JG4 (`fea9792af`) built; archived.**
Board rows `JOB-GRAPH-1` [items: module.elt, layer.product] and `OUTPUT-STORE-SINK-NOT-DRAWN-1` (both closed 2026-11-25). Is P5 of [`2026-11-22-dated-reference-runs-plan.md`](2026-11-22-dated-reference-runs-plan.md) and the graph
side of [`2026-11-22-reference-lookup-plan.md`](../archived-documents/plans-archive/2026-11-22-reference-lookup-plan.md).

## 1. The problem

The work of enriching call records with Reference data is a **Job**: it runs after the records land, reads stores and
writes a store. Today that chain is authored inside the Stream's own onboarding Pipeline (`steps:` + `output_store:`),
and the editor draws it wrong:

```
drawn:    acq → parse → map → join → join → sink (store cdr_enrich)
actual:   acq → parse → map → sink cdr_enrich                     (at ingest: the landed records)
                         └→ join → join → sink cdr_dial_digit      (the Job, after landing: output_store)
```

- The joined rows are drawn going to the landed store; the Job's output store appears nowhere.
- A Reference is a text field on the join (`reference/<pipeline>`), not an edge, so nothing on any canvas shows which
  Reference a Job depends on.
- The Job itself has no graph: opening it shows its config, not *its inputs → Steps → its output*.
- Topology joins Pipelines at shared stores, but cannot show a Job's Reference dependencies.

## 2. The model

```
Pipeline dim_dial_digit      acq → parse → map → ▣ dim_dial_digit      (Reference)
Pipeline dim_service_range   acq → parse → map → ▣ dim_service_range   (Reference)
Pipeline cdr                 acq → parse → map → ▣ cdr                 (Stream)

Job Pipeline cdr_enrich      ▣ cdr ──data──→ join ──→ join ──→ ▣ cdr_enriched   (persistent Dataset)
                             ▣ dim_dial_digit ──reference──↗    ↑
                             ▣ dim_service_range ──reference────┘
```

| Graph | Starts from | Ends in | Runs |
|---|---|---|---|
| Onboarding Pipeline | a Collector | a Dataset (Stream or Reference) | when files arrive |
| **Job Pipeline** | **one data input store** (a Dataset) + **Reference side inputs** | a persistent Dataset | by its Job's Trigger (`on_pipeline`, `cron`, later `by_day`) |
| **Topology** | several Pipelines | joined at shared stores and Reference edges | — (a view) |

- **Data edge vs reference edge:** the data input flows row by row; a Reference is read whole at run time (its current,
  or — with the dated-runs plan — its day's version) and is drawn as a dashed reference edge into its join.
- **The output is a real Dataset** (persistent, partitioned by event day), never a View — a View would recompute the
  joins on every read.

## 3. What already exists (reuse)

| Piece | Where |
|---|---|
| A store as a Pipeline's input | `acquisition.dataset` in `ProcessorCatalog` (`ACQUISITION_SCHEMES`) — to be confirmed in JG0 that it can start a join chain |
| At-rest chain runner | `PipelineJobRunner` (`pipeline_config:` lifts the chain: `PipelineLift.stageTwo`) |
| Reference by name in a Job | `JobService.pipelineConfigs`, fixed 2026-11-22 |
| Graph lift / lower | `PipelineLift`, `PipelineEditable.lower`, `GET …/graph/raw`, `PUT …/graph` |
| Topology view | the Pipelines editor's *Topology* mode — "all chosen pipelines on one canvas, joined at their shared stores" |
| The join Step form | `NodeAttributes.TRANSFORM_JOIN` (keyed form, match modes, range) |

## 4. Editing

### 4.1 The Job Pipeline editor
- **Palette ▸ Inputs ▸ Dataset:** pick a store; it becomes the data input node (`acquisition.dataset`).
- **A join's Reference is an edge:** drag from a Reference Dataset node onto a join; `reference:` follows the edge.
  Removing the edge clears it. The text field stays for a file-path Reference and shows the bound name read-only
  when an edge exists.
- **Column pickers from real columns:** the join's key, `from`/`to`, `columns` and `default` pick from the bound
  Reference's columns; `on` picks from the input's columns flowing into the Step.
- **"Columns this Step adds":** a read-only table under each join (output column · type · from Reference column ·
  looked up by), computed from the Reference's real columns and the Step's settings; a clash or a missing column
  shows before saving (shared with Lookup plan P1d).
- **The sink is a Dataset node:** persistent, its partitioning shown (event day), its name editable.
- **Labels:** each join node shows *Reference · match* (*Dial digits · prefix*, *Service range · range*).
- **Add another lookup:** a join's menu inserts the next join after it, input already wired.

### 4.2 Topology
- **Open by Dataset:** "everything around `dim_dial_digit`" — its onboarding Pipeline, every Job Pipeline reading it
  (as data or as Reference), and their outputs.
- **Draws reference edges** between Pipelines, not only shared Datasets.
- **Navigate:** double-click a Pipeline to open it in the editor.
- **Create in place:** on a store node, *New Job Pipeline from here* (data input prefilled) and, on a Reference node,
  *Join into …* (pick a Job Pipeline; appends a join with the edge drawn).
- **No cross-Pipeline drag editing:** each config file keeps one owner — the Pipeline editor (D-JG1).

### 4.3 Migration from `steps:` + `output_store:`
- **Fix the drawing first** (JG1): the editor draws the landed sink after `map` and the chain in a second branch to an
  `output_store` sink, labelled with the Job that runs it. An untouched save writes the file byte for byte.
- **Split into Job Pipeline** (JG3): a one-click action on such a Pipeline moves the chain into a new Job Pipeline
  (input = the landed store), repoints the Job's `pipeline_config:`, and shows a diff of both files before writing.
- **The old shape keeps loading and running.** Nothing is migrated without the user (D-JG3).

## 5. Phases

| Phase | Builds | Done when |
|---|---|---|
| **JG0 — spike** | confirm `acquisition.dataset` can feed a join chain run by a Job; the run still records which Consignments it read; lift/lower of a Job Pipeline round-trips | a Job Pipeline file runs end to end on the Zain-shaped synthetic data |
| **JG1 — draw the truth** (closes `OUTPUT-STORE-SINK-NOT-DRAWN-1`) | two sinks for `steps:` + `output_store:`; the chain branch labelled with its Job; byte-for-byte untouched save | the demo's `cdr_enrich` draws as §1 *actual* |
| **JG2 — Job Pipeline editing** | Dataset input node; reference edges; column pickers from real columns; "Columns this Step adds"; join labels; *Add another lookup* | a user builds the §2 Job Pipeline in the UI and runs it |
| **JG3 — split** | *Split into Job Pipeline* with a two-file diff | an existing `steps:` + `output_store:` Pipeline becomes the §2 shape in one action |
| **JG4 — Topology** | open by Dataset; reference edges between Pipelines; navigate; create in place | the four graphs of §2 show on one canvas, joined |

## 6. Decisions (open)

- **D-JG1** Topology: read-only with create-in-place (recommended), or full editing across Pipelines.
- **D-JG2** A Job Pipeline is an ordinary Pipeline whose input is a Dataset (recommended), or its own file type.
- **D-JG3** Existing `steps:` + `output_store:` Pipelines: split only on request (recommended), or offered on open.
- **D-JG4** The default Trigger of a new Job Pipeline's Job: `on_pipeline` of its input today; `by_day` once the
  dated-runs plan's P2 lands.
- **D-JG5** Where the Job Pipeline's files live: beside its input's onboarding Pipeline, or under its own folder
  (recommended: its own folder, named after its output Dataset).
