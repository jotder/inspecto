# Pipeline Graph

The authored-**Pipeline** subsystem: an immutable graph IR derived from the flat config, a lift, a
validator, the editable round-trip, and the execution model — layered on top of the
[engine](../engine).

**The as-built truth is this bundle** (2026-09-01 consolidation, layer split): the model here, the
editor UI in [pipeline-editor.md](../../frontend/features/pipeline-editor.md). The ACTIVE redesign
plan is [`superpower/pipeline-spec.md`](../../../superpower/pipeline-spec.md) — a plan, not a
parallel truth; it distills into these concepts as its waves drain.

# Concepts

* [Pipeline graph design](pipeline-graph-design.md) — **the model**: the `PipelineGraph` IR, the
  token rule (edges carry no records), the lift's capability encodings, `PipelineValidator`, the
  `(batch, branch)` commit model, triggers/two drivers, back-pressure, provenance, decisions of
  record. (Design-era 2026-06 prose archived at
  `archived-documents/plans-archive/pipeline-graph-design-era-2026-06.md`.)
* [The editable round-trip](editable-round-trip.md) — `PipelineEditable` lift/lower, every named
  refusal code, `use:` homes and derived refs, the parser family (§20), route branches (§19),
  pipeline settings (§17), dry-run (§18).
* [Execution lanes](execution-lanes.md) — the one table of every lane a pipeline runs in (ingest
  flat / ingest graph-fork / at-rest job / scratch / parked) and which mechanism file owns each.
* [Pipeline config keys](pipeline-config-keys.md) — the census of every `*_pipeline.toon` block:
  who declares it, who reads it, which surface authors it.
* [Live execution](live-execution.md) — running an authored Pipeline as a job
  (`PipelineJobRunner`, `JobType.PIPELINE`, `source_store`/`pipeline_config` seeds).
* [Multi-location ingest](multi-location-ingest.md) — N collector pipelines → one merge flow job +
  the `on_pipeline` all-gate; `collector()` stays singular permanently.
* [Per-Step enabled — park and drain](step-park-drain.md) — switching off a route-branch sink PARKS
  its Consignments durably; `POST /runs/{n}/drain` completes them through the real commit tail.
