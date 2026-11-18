# ELT docs consolidation — ingestion and Pipeline hubs (DOCS-ELT-CONSOLIDATE-1)

Operator ask (2026-11-18): reduce the File ingestion and Pipeline docs to a smaller set by deduplicating,
reorganising and consolidating them, so agents can use them and stakeholders can be pointed at them.
Scope chosen: hubs `module/acquire`, `module/etl`, `module/engine`. Depth chosen: merge, and trim shipped-work
history out of the `plan-*` files (originals archived).

## Ownership rule (what goes where — applies to every merged file)

| Hub | Owns | Does not own |
|---|---|---|
| `module/acquire` | Getting files to the inbox: Connections, Collectors, discovery, stability, dedup at acquisition, unpack | Parsing, Pipelines |
| `module/etl` | What an author writes: Pipeline and Step config, Step catalog, parsing config as authored, the Pipeline Editor, schema/mapping | How the engine executes it |
| `module/engine` | How it runs: ingest SPI, parsers framework, Consignments, the graph model and lift/lower, execution lanes, test run, sinks, persistence, DuckDB | Authoring screens |

A fact that appears in two hubs is kept once, in its owner, and the other place gets a one-line pointer.

## Merge map (old → new)

Generated slots are never edited: `index.md`, `feature-db.md`, `tests-inventory.md`.

### module/acquire (11 → 9)
| New file | Merged from |
|---|---|
| `plan.md` | `plan-acquisition.md` (trimmed: open items + decisions; original archived) |
| `spec.md` | `spec-framework-design.md` + `spec-unpack-stage.md` |
| `user-guide.md` | `user-guide-collectors.md` + `user-guide-collector-config.md` |
| unchanged | `admin-guide.md`, `api-collectors.md`, `architecture.md`, `features-collectors.md` |

### module/etl (17 → 9)
| New file | Merged from |
|---|---|
| `plan.md` | `plan-ingestion.md` + `plan-pipeline-authoring.md` (trimmed; originals archived) |
| `api.md` | `api-parsing-ingest.md` + `api-pipelines-steps.md` |
| `features.md` | `features-parsing-ingest.md` + `features-pipelines-steps.md` |
| `user-guide.md` | `user-guide-pipeline-editor.md` + `user-guide-schema-mapping.md` + `user-guide-parsing-ingest.md` + `user-guide-enrichment.md` + `user-guide-pipelines.md` (MOVED stub) + `spec-first-time-user-use-cases.md` (as "Getting started") |
| unchanged | `admin-guide.md`, `architecture.md`, `spec-step-catalog.md`, `plan-parser-field-tiers-interview.md` (parked kit) |

### module/engine (31 → 15)
| New file | Merged from |
|---|---|
| `architecture.md` | `architecture.md` + `architecture-pipeline-graph.md` + `architecture-stage1.md` (stage-1 history trimmed) |
| `plan.md` | `plan-pipeline-execution.md` (trimmed; original archived) |
| `spec-ingest.md` | `spec-ingestion.md` + `spec-ingest-wrap-spi.md` + `spec-plugin-ingester.md` + `spec-branch-aware-ingest.md` |
| `spec-consignments.md` | `spec-consignment-addressing.md` + `spec-consignment-concurrency.md` |
| `spec-parsing.md` | `spec-parsing-grammar.md` + `spec-parsing-options.md` + `spec-parser-plugins.md` |
| `spec-pipeline-model.md` | `spec-node-types.md` + `spec-catalog-vs-executors.md` + `spec-transforms-seams.md` + `spec-editable-round-trip.md` |
| `spec-pipeline-execution.md` | `spec-execution-lanes.md` + `spec-live-execution.md` + `spec-post-sync-step-chains.md` + `spec-step-park-drain.md` + `spec-pipeline-test-run.md` |
| `spec-sinks.md` | `spec-output-sinks.md` + `spec-object-storage-export.md` |
| `user-guide.md` | `user-guide-runs.md` + `user-guide-run-detail.md` |
| unchanged | `admin-guide.md`, `features.md`, `spec-db-layer.md` and `spec-duckdb.md` (tool-guarded) |

Net: 59 → 33 files.

## Rules for the merge

- Keep every as-built fact, decision (with date), trap and code pointer. Remove only exact or near duplicates,
  superseded text that the file itself marks as superseded, and shipped-work narrative in `plan-*` files.
- A trimmed `plan.md` keeps: purpose/scope, requirements of record, decisions (dated one-liners), not built /
  open, refused & superseded, verification. Shipped "how we built it" prose moves to the owning spec or is cut
  if already there.
- Merged file layout: frontmatter (same keys as the sources: `type`, `title`, `description`, `resource`,
  `tags`, `timestamp`), a 3–6 line "What this covers" summary for stakeholders, a contents list, then sections.
- Vocabulary per `docs/GLOSSARY.md`; citation sections are titled "References".
- Links are written relative to the new file. Old paths are redirected repo-wide by script after the merge.

## Steps

1. Three parallel lanes write the new files (engine-A, engine-B, etl+acquire). They never edit other files.
2. Redirect: every reference to an old path → its new path (repo-wide, `docs/archived-documents/` excluded);
   `git rm` old files; archive the four trimmed originals to `docs/archived-documents/plans-archive/`.
3. `node tools/render-product-docs.mjs`; update `docs/INDEX.md`; guards via pre-commit.
4. Verification agent; commit `docs(etl): …`; push from this worktree; archive this plan.
