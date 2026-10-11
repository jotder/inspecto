---
type: Plan
title: Step column metadata — per-output-column description/unit/classification on a Transform Step, read by the Catalog
description: Design note for AUTHORING-REDESIGN-1 (e) (Parse D2), reopened by the operator 2026-10-11. Where the metadata is stored, how output columns are known, the catalog node shape, rename/remove handling, and the open classification-propagation decision.
resource: platform/inspecto-engine/src/main/java/com/gamma/catalog/MetadataGraphBuilder.java
tags: [authoring, catalog, transform, classification, backlog-row]
timestamp: 2026-10-11T00:00:00Z
---

# Step column metadata (`AUTHORING-REDESIGN-1` (e))

✅ **BUILT 2026-10-11 and ARCHIVED.** Operator decisions 2026-10-11: **Q1 = (B) additive only**, **Q2 = no
`type` on Step columns**. **(C) authoritative is NOT built** (filed as a possible future item in the
BACKLOG row text). As-built facts: [`okf/frontend/features/schema-mapping-authoring.md`](../../okf/frontend/features/schema-mapping-authoring.md) §0.
Deviations from this note: no `STEP_FIELD_UNKNOWN_KEY` / `STEP_FIELD_BAD_CLASSIFICATION` — one code
`ERR_STEP_COLUMN_METADATA_INVALID` refuses a non-string value and metadata on a blank/duplicate name;
unknown other row keys are NOT refused (`fields[]` is opaque and older rows carry extras), and the
classification vocabulary stays the schema's free text. The editor is not offered in the projection slot.

## 1. Grounding (verified against code 2026-10-11)

| Fact | Evidence |
|---|---|
| A `transform.sql` Step is `steps: [{sql: {sql, fields[]}}]`; `fields[]` is opaque to the engine | `PipelineConfig.Step.SQL` javadoc |
| A `fields[]` row is `{id, name, from, fn, args}`; `name` IS the output column (`AS <name>`) and the grid generates the SELECT from the rows, in row order | `inspecto-ui/.../pipeline-transform-sql.ts` `SqlField`, `generateSql` |
| Schema column metadata = `raw.fields[].{description, unit, classification, synonym}` | `SchemaProjection.Column` |
| Catalog columns exist only under `RAW_SCHEMA` (`schema:` → `DESCRIBES` → `col:`); no node for any Step | `MetadataGraphBuilder.addSchemaAndEvent` |
| 🔴 **Schema `classification` is not descriptive only — it drives masking and publication.** `EvidenceMasker.schemaClassification` follows a raw column's class to the stored column; a pipeline with a column-rewriting step (`summarize, sql, lookup, join, route`) and any classified raw column **fails closed to `UNKNOWN_LINEAGE`**. `PostgresPublishJobType` and `PublicationApproval` consume the same lineage | `EvidenceMasker.java:189-231`, `PostgresPublishJobType.java:379-426` |
| The dead-property guard is block-granular (`steps` is one block), so it neither blocks nor checks leaf keys inside a Step | `dead-property-validation-plan.md` §1.1 |

## 2. Where the metadata is stored

**On the `fields[]` row itself** — `{id, name, from, fn, args, description?, unit?, classification?, synonym?}`,
the exact four keys a schema field carries. No new metadata kinds. Rejected: a separate
`columns: {NAME: {...}}` map on the Step — it duplicates the name, so every rename must update two
places and a stale key is a silent orphan.

## 3. How output column names are known

**`fields[].name` is authoritative.** The Simple grid generates the Step's SELECT from its rows, so the
rows ARE the output schema; no dry run is needed and the save stays static. A Step authored in raw-SQL
mode (no `fields[]`) has no static output schema → **no metadata editor and no catalog columns** for it
(stated, not hidden). The dry run is NOT used as a source: it needs data, is non-deterministic across
samples, and the save path is static everywhere else.

## 4. Save validation (422, `ConfigWriteRoutes` / graph PUT findings, ERROR)

For each `sql` Step's `fields[]` row carrying any metadata key:
- the four values must be strings; any other key beyond `{id, name, from, fn, args}` + the four → ERROR
  `STEP_FIELD_UNKNOWN_KEY` (this is the leaf-level dead-property rule for this one shape);
- `classification` must normalise via `ColumnClassification.normalise` (same vocabulary as schemas) →
  else ERROR `STEP_FIELD_BAD_CLASSIFICATION`;
- metadata on a row with a blank/duplicate `name` → ERROR `STEP_FIELD_UNKNOWN_COLUMN` ("unknown column"
  in this shape = metadata that names no distinct output column).
Branch sub-chains (`route.branches[].steps`) get the same check via `Step.branchSteps`.

## 5. Rename / remove

Metadata rides the row, so a rename in the grid carries it and a removed row takes it with it — there is
no orphan state to detect. Reordering is free (row order).

## 6. Catalog node shape (the reader)

A new `NodeKind.STEP` (`step:<pipeline>/<index>`, label `sql #<n>`, attrs `{pipeline, kind: "sql",
index}`), edges `origin → STEP` (`DECLARES`) and `STEP → col:` (`DESCRIBES`), column id
`col:<pipeline>/step<n>/<NAME>` with the SAME attrs a schema column gets (`type` = "" — the grid does not
know DuckDB types statically, `unit`, `classification`), description `Provenance.MANUAL`. And `STEP →
event:` (`MATERIALIZES`) only for the LAST column-shaping Step, since its columns are what lands.
`NodeKind` is `@PublicApi` — adding a constant is additive; the UI catalog must render an unknown kind
generically (to verify at build).

### Q1 — 🛑 OPERATOR DECISION: does a Step column's `classification` propagate like a schema's?

Today a `sql` Step plus a classified raw column makes the store's lineage `UNKNOWN` → fail closed
(mask everything / refuse publication). Step classification could change that, three ways:

- **(A) Catalog-only.** Shown in the Catalog; masking/publication ignore it. Cheapest. ⚠ A column the
  Catalog labels `PII` is then NOT masked by it — a label that looks like a control and is not one.
- **(B) Additive-only (stricter).** Step classifications are MERGED into `schemaClassification` with
  strictest-wins, but the `UNKNOWN_LINEAGE` fail-closed rule stays. Can only ever mask MORE.
- **(C) Authoritative.** A `sql` Step whose every row is classified-or-explicitly-unclassified REPLACES
  the fail-closed rule for that pipeline: the author's declaration becomes the lineage. Fixes the
  over-masking `UNKNOWN` causes today, but lets an author's omission UN-mask a sensitive column —
  a security relaxation.

**Recommendation: (B).** It makes the label a real control without ever relaxing fail-closed, and it
is one merge in `EvidenceMasker.schemaClassification` (+ test). (C) is a security-posture change that
should be its own row. (A) only if the operator wants the smallest slice and accepts the misleading
label (then the editor should say "catalog label only — does not mask").

### Q2 (minor, recommendation stands unless overruled) — `type` on Step columns

Leave `type` blank (no static type). Alternative: stamp the last Test-run's types — rejected, a stored
type from a sample is a stale-able guess.

## 7. Build slices (after Q1)

1. Backend validation + real-HTTP 422 test (`ControlApiStepColumnMetadataTest`).
2. `NodeKind.STEP` + projection in `MetadataGraphBuilder` + catalog read test; (B) merge in `EvidenceMasker` if chosen.
3. UI: per-row Describe disclosure on the Record Transformer grid (description · unit · classification
   select · synonym), vitest + axe.
4. Docs as-built → `okf/frontend/features/schema-mapping-authoring.md` §0; archive this plan.
