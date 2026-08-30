# Pipeline Graph

The authored-**Pipeline** subsystem: an immutable graph IR, a lift from legacy configs, a validator, and an
executor — layered on top of the [engine](../engine). Authored Pipelines run as
[`JobType.PIPELINE`](live-execution.md) jobs (`type: pipeline` in job config).

# Concepts

* ⚠ **[The consolidated Pipeline specification](../../../superpower/pipeline-spec.md) is the single place the Pipeline is described** (2026-08-30) — files, the derived graph, Step types and connection rules, execution, extension seams, transfer, and the honest gap list. The concepts below remain the deep detail for their own areas; when one disagrees with the spec, fix one of them rather than diverging.
* [Design](design.md) - the `PipelineGraph` IR, `PipelineLift`, `PipelineValidator`, `PipelineExecutor`, and the node-type registry.
* [Live execution](live-execution.md) - running an authored Pipeline end-to-end (`PipelineJobRunner`, `source_store` seeds, conservation checks).
* [Multi-location ingest](multi-location-ingest.md) - the composition pattern (N collector pipelines → one merge flow job + the `on_pipeline` all-gate); `collector()` stays singular permanently.
* [Per-Step enabled — park and drain](step-park-drain.md) - switching off a route-branch sink PARKS its Consignments durably at the boundary; `POST /runs/{n}/drain` completes them through the real commit tail (D-13).
* [Pipeline graph design (full design)](pipeline-graph-design.md) - the authoritative deep design incl. §14 backlog (moved from `docs/flow-graph-design.md`).
