---
type: Concept
title: Pipeline closure — what belongs to a pipeline
description: The closure query and why reference direction is irreducibly inconsistent by design — what it returns, how it is built by joining two existing halves, and two rules that look like details and are not.
resource: inspecto/src/main/java/com/gamma/control
tags: [pipeline, closure, references, catalog, related]
timestamp: 2026-08-18T00:00:00Z
---

# Pipeline closure — what belongs to a pipeline

**Route:** `PipelineRelatedRoutes` — `GET /pipelines/{name}/related`.
**Service:** `com.gamma.service.PipelineRelated`. Shipped 2026-08-31 (pipeline spec §12 gap 5, decision D9).

## The problem it closes

Reference direction is inconsistent, by design and irreducibly: a **pipeline points out** to its schema,
mapping and grammar, while an **enrichment, job or dataset points in** at the pipeline. So every caller
that wanted *"everything belonging to this pipeline"* — bundle export first among them — had to know
that and scan both ways itself. This is the server-side closure, computed once, where the rules live.

## What it returns

```json
{
  "pipeline": "cdr_ingest",
  "references": [
    { "kind": "grammar", "ref": "grammar/cdr", "path": "registry/grammars/cdr.toon" },
    { "kind": "file", "path": "cdr_schema.toon" }
  ],
  "dependents": { "job": [ { "name": "nightly_job", "via": "triggers.on_pipeline" } ] },
  "total": 1,
  "truncated": false
}
```

`references` is the outward half, `dependents` the inward one. The inward shape and its
`total`/`truncated` deliberately mirror `GET /config/pipeline/{name}/impact`, so the two reads read alike.

## How it is built — two existing halves, joined

⛔ **Neither half is a new traversal.** That is the point of the design, not an implementation detail.

| Half | Comes from | Notes |
|---|---|---|
| **Inward** | `PipelineDependents.scan` | Already serving `/config/pipeline/{name}/impact`. Reports a *superset* of D9 (enrichment · job · expectation · decision-rule · dataset · widget · dashboard), bounded by `MAX_DEPENDENTS` with a TRUE total. Reused verbatim — one scan, one set of matching rules, one place to fix. |
| **Outward** | `PipelineConfig.referencedFiles()` | The files the parser **actually read**. Deliberately *not* re-derived from config keys: a second reader of the same config is exactly the drift `PipelineKeyCoverageContractTest` exists to stop. |

## Two rules that look like details and are not

**A `kind` is claimed only where it is certain.** A file under `<writeRoot>/registry/<dir>/` *is* that
component type — the directory says so — and gets the canonical `<type>/<id>` ref an import applies.
⚠ A config may spell the same ref singular or plural (`grammar/x` and `grammars/x` both resolve); the
reported ref is always the singular canonical type, so a caller never normalises. A plain path is
reported as `kind: "file"` with its real location rather than guessed at from its suffix.

🔴 **Completeness does not depend on that labelling.** Every file the parser read is reported either
way — which matters *today*, not hypothetically: the parser also picks up a **sibling mapping CSV by
convention, with no config key naming it**. Reporting only key-explained files would silently drop it
and an import would lose the mapping. If a precise kind for plain paths is ever wanted, the honest fix
is for `PipelineConfig` to carry the provenance, never for a second reader to infer it here.

## Deliberate boundaries

- ⛔ **Connections are excluded** (D9, operator's call): they carry environment and credentials, and a
  bundle that moved them would move a deployment's identity between spaces. The exclusion is structural
  twice over — a Connection is resolved at run time and never enters `referencedFiles()`, *and*
  `connections` is absent from the registry-dir map. ⚠ **Measured:** adding `connections` to that map
  does **not** fail `ControlApiPipelineRelatedTest.neverReportsAConnection`, because the first reason
  already makes it unreachable. The test pins the observable guarantee; the map omission is defence in
  depth and is unfalsifiable from that route. If a Connection ever becomes a parsed reference, that test
  starts carrying the weight.
- ⚠ **It answers for a REGISTERED pipeline, not a file on disk.** The outward half is
  `referencedFiles()`, which exists only once the parser has read the config. A `*_pipeline.toon` under
  the write root that no `CollectorService` loaded is a **404 here and a 200 on `/impact`** — not an
  inconsistency, but the difference between *what is deployed* and *what is on disk*.
- ⚠ **The 503 is a scan-root dependency, not a write gate on a read route.** The inward half must walk
  the configs under the write root; without one there is no corpus. Answering with only the outward half
  would be a partial closure presented as a whole — the trap for a caller asking "what does an import
  need".

## Over a diff — which Pipelines a change reaches (duckle C7, first slice)

`com.gamma.service.AffectedPipelines` (2026-09-24, `DUCKLE-C7-AFFECTED-CONTRACTS-1`) asks the same
question in the other direction and **over a change set instead of one Pipeline**: given the files a
revision touched, which Pipelines does it reach, each with the chain that reached it? It is a **CI-shaped
check, not an in-app git feature** (the operator's 2026-09-15 ruling), and it builds no lineage model of
its own — every edge is one the product already reads:

| Edge | Read through |
|---|---|
| file → Pipeline (child → parent) | `ConfigRegistry` + `PipelineConfig.referencedFiles()`, the outward half above |
| producer Pipeline → Dataset | `PipelineDependents.scan` (`sourceName` / `physicalRef` head), the inward half above |
| Dataset → consumer Pipeline | `collector.dataset`, the id `connector: dataset` resolves |

Propagation is breadth-first, so a chain is the shortest one, e.g.
`file:prod/prod_schema.toon -> pipeline:prod -> dataset:prod_ds -> pipeline:cons`.

- **Deleting a producer is a change.** A deleted `*_pipeline.toon` is reported (id from its pre-change
  `name`), and the Datasets still naming it lead on to their consumers. A deleted file a surviving
  Pipeline read makes that Pipeline fail to load; the load failure names the file, so it is reported as
  reached **and** as uncertain.
- **Formatting is not a change.** A modified `.toon` whose decoded content is equal before and after
  reaches nothing. Canvas geometry needs no rule: node positions live in browser storage
  (`pipeline-layout.ts`), never in config.
- **Uncertain, never resolved:** a Pipeline that does not load; a `collector.dataset` that is not a bare
  Dataset id (templated `${…}` or path-shaped `datasets/x`); a deleted Pipeline with no pre-change content
  (id taken from the file name).
- **Entry point:** `AffectedPipelines.main <configRoot> <baseRev> [--fail-on-affected] [--fail-on-breaking]`
  diffs `baseRev` against the working tree with `git diff --name-status --no-renames` (a rename is a delete
  + an add) and reads pre-change content with `git show`. Exit 1 only when asked: `--fail-on-affected` when
  something is affected or uncertain, `--fail-on-breaking` when any verdict is BREAKING.
  ⚠ It hosts no Space, so it needs `-Dassist.safety.roots=<space base>`: without a jail root every Pipeline
  fails to load (*no allowed roots configured*) and the report is all UNCERTAIN, judging nothing — measured
  over `spaces/ucc/config` on 2026-09-24 before the CI step passed it.
- **Non-Pipeline dependents** (`Report.dependents()`, CLI lines `DEPENDENT <kind>:<name>  via <key>  <chain>`):
  every `PipelineDependents.scan` hit of every reached Pipeline — enrichment (`references.<n>.ref`,
  `triggers.on_pipeline`), job (`on_pipeline`), Expectation / Decision Rule (`target`), Dataset, Widget,
  Dashboard — each with the reached Pipeline's chain plus itself, first (shortest) chain kept. A directly
  changed Dataset adds its own Widgets and their Dashboards, so a Dataset shown on a Dashboard but consumed by
  no Pipeline is reported, not IGNORED. They are **listed, not followed**: what an enrichment or a job then
  writes is not traced on. A scan cut at `PipelineDependents.MAX_DEPENDENTS` is UNCERTAIN, never silent
  (`AffectedPipelinesTest.enrichmentAndJobLinksOfEveryReachedPipelineAreListedWithTheirChains`,
  `…aChangedDatasetListsItsWidgetsAndDashboardsEvenWithNoConsumer`).
- **`collector.dataset: datasets/<id>`** is the Dataset link, not uncertain: the check reads the parsed
  `PipelineConfig.collector().dataset()`, which the parser has already stripped of that prefix
  (`AffectedPipelinesTest.aDatasetsPrefixedReferenceIsTheDatasetLinkNotUncertain`).

### Contract verdicts — judged by the reader (second slice, 2026-09-24)

`com.gamma.service.ContractVerdicts` adds `Report.verdicts()`, rendered in the CLI output as one line per verdict prefixed by its tier.
**Four tiers, never collapsed** — the middle two are the reason the check exists:

| Tier | When |
|---|---|
| **BREAKING** | a removed or **retyped** column that a reader in config reads; every reader of a **deleted producer** (its enrichment and job links included) or a **deleted Dataset** |
| **POSSIBLY_BREAKING** | a removed or retyped column that **no reader in config is known to read** — never "compatible", because a reader outside config (a query, a BI tool, an export) may; emitted alongside any REVALIDATE for a reader whose use is unknown |
| **REVALIDATE** | the producer's output or a reader's column use **cannot be determined** — a transform step, free SQL, a non-parquet consumer — and everything downstream of a reader that broke or cannot be judged |
| **ADDITIVE** | a new column: stated explicitly, because it is not a contract break, not because it was left out |

**Which Pipelines are judged.** Only a *directly* changed one (chain `file → pipeline`). Its output columns
before and after are `TypeFlow.sinkColumns` — DuckDB `DESCRIBE` over the SELECT the engine runs, the
written table's shape — so types are DuckDB's own, and names compare case-insensitively. The pre-change
side is rebuilt, not assumed: a changed `*_pipeline.toon` is re-parsed from its `git show` content with
`PipelineConfig.fromMap(raw, configDir)` (so a `threads:` edit has no verdict), and a changed schema file is
substituted with its pre-change content — **only when it is the Pipeline's whole single schema** (equal
to `schemas().single()`). A changed grammar, sibling structure/mapping file, a multi-schema Pipeline, a
schema that does not compile, or a Pipeline that no longer loads cannot be diffed ⇒ its readers are
REVALIDATE with that reason. A newly added Pipeline has no prior contract and gets no verdict.

**Only `filter` / `dedup` / `lookup` steps keep the output known** (`lookup` adds its `target`) — the rule
`ConfigRoutes.walkSteps` applies at save. Any other step (`sql`, `join`, `summarize`, `profile`, `route`)
means there is no column lineage through it ⇒ REVALIDATE, not a guess.

**Readers, and how their column use is known** (no SQL is parsed anywhere):

| Reader (of a Dataset the producer feeds) | Column use | Undeterminable ⇒ REVALIDATE |
|---|---|---|
| consumer Pipeline (`collector.dataset`) | parquet frontend: `raw.fields[].selector` IS the parquet column name (`DuckDbCsvIngester.buildParquetReadSpec`) | any other frontend, a multi-schema consumer, one that does not load |
| the Dataset itself | declared `columns[].name` (none declared = reads nothing by name) | it has `calculated` columns (free SQL) |
| Widget (`datasetId`) | every `controls.*.field` | a `queryId` (saved query, free SQL), or no `controls` at all (shows the columns wholesale) |
| enrichment (`references.<n>.ref`, `triggers.on_pipeline`), job (`on_pipeline`), Expectation / Decision Rule (`target`) — they name the **Pipeline**, not a Dataset | never known: enrichment and rule bodies are free SQL, a job reads whatever its type does | **always** — per changed column, and on the whole-producer paths |

**What "reads" means, and why it fails toward reporting.** A reader *reads* a column only when structured
config names that column (the three rows above with a column-use rule). A reader whose use is not in
structured config is never treated as reading nothing — it is REVALIDATE, the weaker-certainty verdict, and
the column itself stays POSSIBLY_BREAKING rather than BREAKING, because nothing proves the read. Only a
proven read is BREAKING; only a proven non-read by every reader leaves POSSIBLY_BREAKING standing alone.

**Downstream.** A consumer that BREAKS, or cannot be judged, taints its own Datasets: every reader past it
is REVALIDATE (*"column lineage past a consuming Pipeline is not traced"*), its enrichment / job / rule
links included. A consumer that provably reads none of the changed columns taints nothing.

**Dataset definitions.** A deleted `registry/datasets/<id>.toon` is BREAKING for its consumers and Widgets;
a modified one is REVALIDATE for them only when `physicalRef`, `sourceName`, `columns` or `calculated`
changed (a description or tag edit has no verdict).

**Mutation-checked (2026-09-24).** Each rule was reverted in turn and the two test classes re-run
(23 tests): a read column not BREAKING, an unread column dropped, a transform step treated as
shape-preserving, an unknown reader treated as reading nothing, no downstream taint, no Pipeline-level
readers, no ADDITIVE, no dependents listed, no Widgets on a changed Dataset — every one of the nine turned
at least one test red.

### CI wiring (2026-09-24)

`.github/workflows/ci.yml`, job `test`, step *Report — Pipelines, dependents and contracts this PR's config
change reaches*, after the reactor `install` (it runs the shaded `inspecto/target/inspecto-processor-*.jar`
that step builds). `pull_request` only; one run per `spaces/*/config` root, base `HEAD^1` — GitHub's PR merge
commit's first parent is the base tip, so the diff is exactly the PR (the checkout has `fetch-depth: 2`).
**It runs offline**: the checked-out tree, `git show` of the base, and the DuckDB embedded in the fat JAR —
verified by running the same loop locally against all three Spaces. **Report only by default**: every exit is
a warning. The repository variable `AFFECTED_CONTRACTS_FLAGS` (`--fail-on-breaking`, `--fail-on-affected`)
turns it into a gate.

- ⛔ **Deferred:** Measures in materialize / report jobs and saved queries parsed for their columns (a
  saved-query Widget and every enrichment / job / rule link are REVALIDATE today); column lineage *through*
  a consumer or an enrichment (its downstream is REVALIDATE or merely listed, never judged column by
  column); diffing a multi-schema or plugin producer; Alert Rules — `AlertRule` carries `onPipeline` and
  `dataset`, but `PipelineDependents` (kept key-for-key with the rename path) has no Alert Rule scanner, so
  an Alert Rule is neither listed nor judged. That is an under-report, the one gap here that fails the
  wrong way; it belongs with the scanner, not with this check.

## Related

* [Metadata bundle](metadata-bundle.md) — the export/import surface this closure is meant to feed
  (pipeline spec gap 6(a): bundle export should call `related` instead of deriving the closure in the UI).
* [Catalog lifecycle / delete impact](../../frontend/features/catalog.md) — `GET /config/pipeline/{name}/impact`,
  the inward half's original consumer.
