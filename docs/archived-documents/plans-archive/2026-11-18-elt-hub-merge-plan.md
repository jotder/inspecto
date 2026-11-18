# ELT hub merge — one hub for ingestion and Pipelines (DOCS-ELT-CONSOLIDATE-1, phase 2)

Operator ask (2026-11-18): fewer files again, and ETL → ELT. Decisions: the Product Map item `module.etl` is
renamed `module.elt` (done, commit `b7feb3bc0`; the build module stays `etl`). The docs of `module.acquire` and
`module.engine` that cover ingestion and Pipelines move INTO the `module/elt` hub. The Product Map items stay
three (each maps to one build module via its `module:` key, which feeds the generated `feature-db.md`), so
`module/acquire` and `module/engine` keep only their generated files plus a `slots-na.toon` that points here.
`engine/spec-db-layer.md` and `engine/spec-duckdb.md` stay in the engine hub (persistence and DuckDB, not ELT;
tools hard-code their paths).

## Target: module/elt (10 hand-written files)

Order of the story everywhere: acquire → parse/ingest → Pipeline (model, execution) → sinks → operate.

| New file | Merged from (in this order) |
|---|---|
| `architecture.md` | `elt/architecture.md` + `acquire/architecture.md` + `engine/architecture.md` |
| `plan.md` | `elt/plan.md` + `acquire/plan.md` + `engine/plan.md` + `elt/plan-parser-field-tiers-interview.md` (as a parked appendix) |
| `spec-acquisition.md` | `acquire/spec.md` (moved as is) |
| `spec-ingest.md` | `engine/spec-ingest.md` + `engine/spec-consignments.md` + `engine/spec-parsing.md` |
| `spec-pipeline.md` | `engine/spec-pipeline-model.md` + `engine/spec-pipeline-execution.md` + `engine/spec-sinks.md` |
| `spec-step-catalog.md` | unchanged |
| `user-guide.md` | `elt/user-guide.md` + `acquire/user-guide.md` + `engine/user-guide.md` |
| `admin-guide.md` | `elt/admin-guide.md` + `acquire/admin-guide.md` + `engine/admin-guide.md` |
| `api.md` | `elt/api.md` + `acquire/api-collectors.md` |
| `features.md` | `elt/features.md` + `acquire/features-collectors.md` + `engine/features.md` |

Net for the three hubs: 33 → 12 hand-written files (10 in `elt`, 2 in `engine`), plus generated files.

## Merge rules

- Keep every fact. Remove only text that is now duplicated inside the merged file, and cross-hub pointer
  lines that now point into the same file (turn them into "see § X above/below").
- **Section numbers:** the FIRST source keeps its numbering unchanged. Later sources are appended as new
  top-level parts that continue the numbering or take a letter prefix; report an old → new section map per
  source so outside "§" citations can be rewritten.
- One H1 per file. A merged file opens with "What this covers" (3–6 lines) and a contents list.
- "ETL" in prose that names this layer becomes "ELT". Code identifiers stay (`inspecto-etl`, `com.gamma.etl`,
  `platform/inspecto-etl/`).
- Vocabulary per `docs/GLOSSARY.md`; citation sections are titled "References".
- Links are written relative to `docs/okf/product/module/elt/`; old paths are redirected by script afterwards.
