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
- **Entry point:** `AffectedPipelines.main <configRoot> <baseRev> [--fail-on-affected]` diffs `baseRev`
  against the working tree with `git diff --name-status --no-renames` (a rename is a delete + an add) and
  reads pre-change content with `git show`. Exit 1 only when asked and something is affected or uncertain.
- ⛔ **Deferred:** the contract verdicts (breaking / possibly breaking / revalidate, by reader) — the
  larger half of the row; non-Pipeline dependents (enrichment, job, Expectation, Widget) in the output;
  enrichment `references.<n>.ref` and job `on_pipeline` as Pipeline-to-Pipeline edges; a CI wiring.

## Related

* [Metadata bundle](metadata-bundle.md) — the export/import surface this closure is meant to feed
  (pipeline spec gap 6(a): bundle export should call `related` instead of deriving the closure in the UI).
* [Catalog lifecycle / delete impact](../../frontend/features/catalog.md) — `GET /config/pipeline/{name}/impact`,
  the inward half's original consumer.
