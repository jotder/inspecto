---
type: Plan
title: module.elt - board row detail
description: The narrative behind module.elt's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, elt, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.elt
audience: internal
status: plan
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# module.elt - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### AUTHORING-REDESIGN-1

- **AUTHORING-REDESIGN-1** — **the authoring redesign's still-open letters.** *Re-ranked P2 → P3, `hold: demand`, 2026-11-12 (product assembly plan §13.1): only (d) is left and the operator parked it until a named consumer exists; still no `CREATE MACRO` in any main source.* (c), the structured AST table over the row predicate, is COMPLETE (2026-09-23; step 5 `transform.join` out of scope by design), and (f)(g)(j)(l)(m)(n2)(o) shipped, and (i) — the Parse pane's per-row "sample resolves to" line, over an additive `resolved` `sniff_csv` map on the delimited previews — shipped 2026-09-24 (`okf/product/module/config/user-guide-grammar-config.md`). Open: **(d)** v3 macros as the UDF registry — per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `ConsignmentIngestStrategy` and preview — **PARKED ON DEMAND (operator 2026-10-04: stays parked until a named consumer exists)**; re-grounded: no `CREATE MACRO` in any source, the JDBC driver has no scalar-UDF API, and it needs a macro component kind + registry that do not exist; if ever built, recommended shape is a Space-level `macros/*.toon` registry loaded by one shared helper on every scratch connection (2A) governed by the existing Safety Policy tiers + SqlGuard/ExpressionGuard + seal (3A); **(e)** column metadata editing on the Transform pane (Parse D2) — ✅ **BUILT 2026-10-11** (reopened by the operator after the 2026-10-04 deferral): a chain `sql` Step's `fields[]` rows carry the schema's four metadata keys, the save refuses malformed ones (`ERR_STEP_COLUMN_METADATA_INVALID`), the Catalog reads them as a `STEP` node with COLUMN children, and a sensitive Step classification joins the masking lineage ADDITIVELY (operator Q1 = (B); never clears fail-closed) → `okf/product/module/elt/user-guide-elt.md` §8.2; possible future item, not built: option **(C)** — a Step's declared classifications REPLACING the fail-closed `UNKNOWN_LINEAGE` rule for that pipeline (fixes over-masking but lets an omission unmask a column; a security-posture change needing its own decision); and on (f), which COLUMNS a reference carries is the dry run's question (the save checks existence and `on` presence only). → `archived-documents/plans-archive/authoring-ast-table-design.md` · `okf/product/module/elt/user-guide-elt.md` §8.2

### Step Processor catalog

- **Step Processor catalog** — ✅ **The 2026-09-23 hold on new Step Processors was LIFTED (operator, 2026-10-06)** — the operator named `transform.analytics.running` first (delivered 2026-10-06 as `transform.running`); `sink.file.excel` was also named and DELIVERED 2026-10-06 as `sink.excel`. Build the next one only when the operator names it. 122<!--count:processors--> processors: **54**<!--count:processors-delivered--> delivered / **17**<!--count:processors-partial--> partial / 51 planned (`processor-catalog.contract.json`). Each partial is its own product decision (Kafka consumer, XPath grammar, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email sinks…) — pick one by name. ⚠ A catalog entry is a `BuiltinNodeType` case compiled by `RecipeCompiler`/`ProcessorCatalog`, **not** the `ConsignmentProcessor` SPI; the namespace is `transform.*`; a new node type needs a flat-config home too; and both contract JSONs under `inspecto-ui/` are generated from Java and must be regenerated in the same change. → `EDITIONS.md` §Step Processors · `okf/product/module/elt/spec-step-catalog.md`

### BASIC-ELT-PROFILE-1

- `BASIC-ELT-PROFILE-1` — **Basic onboarding as a locked profile of ELT: one implementation, Basic = options locked.** Basic already runs a registered Pipeline, but a parallel layer of about 6,700 backend lines (own 30 s poller, own follow-on seam, arrival rules, sniff and Postgres path in `inspecto/.../control/`) and about 9,400 UI lines in the LA project duplicate ELT parts. Phases: P0 verify (backend, UI), UI-0 move Add data to the core library, UI-1 reuse the schema / connection / grammar / Collector components, BE-1 poller and follow-on, BE-2 arrival rules as engine Pipeline options, BE-3 sniff, BE-4 Postgres as a connector scheme, then the Edition gate. → `superpower/2026-11-19-basic-elt-profile-plan.md`

### BASIC-WATCH-ARRIVALS-1

- `BASIC-WATCH-ARRIVALS-1` — **A watched inbox's scheduled run leaves no arrivals sidecar line and no `watchError` for a quarantined file (filed 2026-11-19, `BASIC-ELT-PROFILE-1` B1 review).** Since B1 *Watch this folder* is the import Pipeline's own 30 s schedule, run by the engine; Add data's in-run `Job.recordArrival` (sha256, index outcome, kept columns per arrival) and the poller's `watchError` for a run that set the file aside no longer happen for it. The arrivals list still shows each file from the engine's file ledger, refusals are still recorded by the pre-run step, and a run that sets aside a file whose column the pre-run step kept does set `watchError` and takes the column back (read from the quarantine folder, so under either status store). Operator 2026-11-19: restored in BE-2 (B3, the arrivals as an engine Pipeline option), not in B1. → `../la-core/architecture-watch-this-folder.md` §6.20

## Standing refusals and decisions

Board §6: not work — kept so nobody re-files it.

### transform.merge attributes

- **`transform.merge` attributes — REFUSED 2026-09-07, the same day MERGE-ATTRS-1 was filed as a defect.**
  The row was right that `RowShaper.merge` reads `type` (`union`|`inner`|`left`) and `on` off the node config
  and that `NodeAttributes` declares neither. Its **cause and severity were both wrong.** `transform.merge`
  is absent from `PipelineEditable.LOWERABLE` (and was from the since-retired `RECIPE_VERBS`) **by decision** — the same set as
  `transform.split`/`select`/`derive`/`validate`, and `PipelineEditable:196` says admitting them would
  "silently reverse all of those". A graph carrying one therefore **refuses at save** with
  `UNSUPPORTED_NODE` (422; pinned by `ControlApiPipelineCrudTest` on the sibling `transform.derive`), and
  neither `PipelineJobRunner` graph source can carry one: the flat `pipeline_config:` path has no home for
  it, and the `pipeline:` path reads the store that refused it. So the node is executable code with **no
  authoring or persistence route at all** — it is not "stuck on union", it is unreachable. ⛔ Declaring
  attributes would hand a config pane to a node that cannot be saved, which is the exact defect the
  `transform.sql` flat-config-home lesson records. The authorable successor is the PLANNED Step Processor
  `transform.join.merge`, whose catalog entry already calls `transform.merge` "the grandfathered … read-only
  ancestor". Reopen only as an operator decision to make merge authorable — that is the four-registration
  recipe plus a deliberate reversal of a standing refusal, not a one-line attribute table.
