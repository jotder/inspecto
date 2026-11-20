---
type: Feature
title: ELT — user guide
description: One guide to building and running a Pipeline, from a blank install to a queryable Dataset — the first-run journey and use cases, the Pipeline editor (shell, canvas, Step configuration, Parse, schema authoring, the Record Transformer and the transform.sql pane, joins, filters, enrichment, sinks and route branches), ingest options and feed contracts, testing, saving and history, pipeline-level surfaces, guided mode, the Collectors and Connections screens with the one collector-block form, and the Runs and Run Detail screens.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipelines.routes.ts, inspecto-ui/src/app/modules/admin/pipelines/pipeline-transform-sql-definition.component.ts, inspecto-ui/src/app/modules/admin/enrichment/enrichment.routes.ts, inspecto-ui/src/app/modules/admin/collectors/collectors.routes.ts, inspecto-ui/src/app/inspecto/collector/collector-config.component.ts, inspecto-ui/src/app/modules/admin/runs/runs.routes.ts
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# ELT — user guide

**What this covers.** How a person turns a file into a Dataset they can query, and how the **Pipeline
editor** works when they do it by hand. It starts with the first-time journey (who the first user is, the
steps they take today, the nine use cases the product is built against), then follows a Pipeline in build
order: the editor, collecting, parsing, the schema, transforming and mapping, enriching, loading into sinks,
ingest options, testing, saving, and the screens that show a Run. Two appended sections cover the
**Collectors** and **Connections** screens (§21) and the **Runs** and **Run Detail** screens (§22).

**Contents**

1. [Getting started — from a blank slate to a queryable Dataset](user-guide-elt-getting-started.md#1-getting-started--from-a-blank-slate-to-a-queryable-dataset)
2. [The Pipeline editor — shell, lenses, tabs and docks](user-guide-elt-getting-started.md#2-the-pipeline-editor--shell-lenses-tabs-and-docks)
3. [Steps — the vocabulary, the flags and the authoring model](user-guide-elt-steps.md#3-steps--the-vocabulary-the-flags-and-the-authoring-model)
4. [Configuring a Step — the definition drawer](user-guide-elt-steps.md#4-configuring-a-step--the-definition-drawer)
5. [Collect — the `acquisition` Step](user-guide-elt-steps.md#5-collect--the-acquisition-step)
6. [Parse — drawer vs dialog, the claimed slot and the sample thread](user-guide-elt-parse-schema.md#6-parse--drawer-vs-dialog-the-claimed-slot-and-the-sample-thread)
7. [Schema authoring](user-guide-elt-parse-schema.md#7-schema-authoring)
8. [Transform and map — the Record Transformer](user-guide-elt-record-transformer.md#8-transform-and-map--the-record-transformer)
9. [Enrich](user-guide-elt-enrich-load-test.md#9-enrich)
10. [Load — sinks, partitioning and route branches](user-guide-elt-enrich-load-test.md#10-load--sinks-partitioning-and-route-branches)
11. [Ingest options and feed contracts](user-guide-elt-enrich-load-test.md#11-ingest-options-and-feed-contracts)
12. [Testing what you author](user-guide-elt-enrich-load-test.md#12-testing-what-you-author)
13. [Save, validation, refusals and history](user-guide-elt-save-surfaces.md#13-save-validation-refusals-and-history)
14. [Pipeline-level surfaces](user-guide-elt-save-surfaces.md#14-pipeline-level-surfaces)
15. [Guided mode — the onboarding host](user-guide-elt-save-surfaces.md#15-guided-mode--the-onboarding-host)
16. [Runs and processing status](user-guide-elt-save-surfaces.md#runs-and-processing-status)
17. [The Recipe view and the step cards (stale record)](user-guide-elt-save-surfaces.md#17-the-recipe-view-and-the-step-cards-stale-record)
18. [Known gaps](user-guide-elt-save-surfaces.md#18-known-gaps)
19. [Verification culture](user-guide-elt-save-surfaces.md#19-verification-culture)
20. [References](user-guide-elt-collectors-runs.md#20-references)

**Reading order.** §21 and §22 joined this guide on 2026-11-18, when the Collectors and Runs guides were
merged into it. They are appended so that §1–§20 keep their numbers, and they sit just before §20
References, which stays last. Read §21 with §5 (it is the full contract of the Collect Step, before §12
testing and §13 saving), and §22 with §16.

21. [Collectors and Collector configuration](user-guide-elt-collectors-runs.md#21-collectors-and-collector-configuration) — the Collectors and
    Connections screens and the one `collector:` form (§21.1–§21.8)
22. [Runs and Run Detail](user-guide-elt-collectors-runs.md#22-runs-and-run-detail) — the Runs list and the Run Detail tabs (§22.1–§22.2)

> **Scope and owners.** This guide owns the **editor UI** truth (operator decision 2026-09-01: single source of
> truth, split by layer). It defers to:
> - [Grammar configuration](../config/user-guide-grammar-config.md) — the Parse pane's internals and everything Grammar;
> - [§21 Collectors](user-guide-elt-collectors-runs.md#21-collectors-and-collector-configuration) (below) — the `acquisition` Step surface;
> - [Inline AI authoring](../intelligence/user-guide-inline-ai-authoring.md) — the `<inspecto-ai-assist>` adoption;
> - [`okf/backend/pipeline-graph/`](../../index.md) and the [Pipeline spec — model](spec-pipeline-model.md) —
>   the backend Pipeline model (lift/lower/validator/executor, the token model);
> - [`archived-documents/plans-archive/pipeline-spec.md`](../../../../archived-documents/plans-archive/pipeline-spec.md) — **archived 2026-09-10**, provenance only.
>
> **Current truth only** — superseded states are kept only where knowing them prevents re-breaking something.
> The dated change-log forms of the merged pages are in git history and the archived plans they cite.
>
> **Vocabulary.** The canonical term is **Step** (GLOSSARY §2). ⚠ The UI copy and much of the code
> still say "node" (`node-attributes.ts`, `/pipelines/node-types`, …) — the Node→Step rename is
> tracked in GLOSSARY §13 as NOT STARTED. This guide writes *Step* and quotes code names verbatim.
> Product copy never says "wizard"; this guide says **guide** for step-by-step help.

---

## Parts

This page is split by topic into 7 parts; each part lists the sections it holds.

1. **[Getting started and the Pipeline editor](user-guide-elt-getting-started.md)** — 1. Getting started — from a blank slate to a queryable Dataset · 2. The Pipeline editor — shell, lenses, tabs and docks
2. **[Steps, Step configuration and Collect](user-guide-elt-steps.md)** — 3. Steps — the vocabulary, the flags and the authoring model · 4. Configuring a Step — the definition drawer · 5. Collect — the `acquisition` Step
3. **[Parse and schema authoring](user-guide-elt-parse-schema.md)** — 6. Parse — drawer vs dialog, the claimed slot and the sample thread · 7. Schema authoring — board rows: `AUTHORING-REDESIGN-1`
4. **[Transform and map — the Record Transformer](user-guide-elt-record-transformer.md)** — 8. Transform and map — the Record Transformer
5. **[Authoring gap list, enrich, load, ingest options and testing](user-guide-elt-enrich-load-test.md)** — 9. Enrich · 10. Load — sinks, partitioning and route branches · 11. Ingest options and feed contracts · 12. Testing what you author
6. **[Save, pipeline-level surfaces, guided mode and known gaps](user-guide-elt-save-surfaces.md)** — 13. Save, validation, refusals and history · 14. Pipeline-level surfaces · 15. Guided mode — the onboarding host · Runs and processing status · 17. The Recipe view and the step cards (stale record) · 18. Known gaps · 19. Verification culture
7. **[Collectors, Runs and references](user-guide-elt-collectors-runs.md)** — 21. Collectors and Collector configuration · 22. Runs and Run Detail · 20. References
