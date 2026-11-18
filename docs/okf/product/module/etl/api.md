---
type: Reference
title: "Pipelines, parsing and ingest — API"
description: "Control-plane routes for authoring Pipelines and Steps, previewing parsers, and reading and acting on Runs, with their gates."
tags: [etl, pipelines-steps, parsing-ingest, api]
timestamp: 2026-11-18T00:00:00Z
---

# Pipelines, parsing and ingest — API

**What this covers.** The HTTP routes an author or an operator calls to build a Pipeline and to watch it
load data: listing and saving Pipelines, the Step palette, dry runs and test runs, saved versions, parser
previews, and the Run-detail ledgers and actions. It is the route index only; how each route behaves is in
the [user guide](user-guide.md) and the design in [architecture](architecture.md).

**Contents**

- [Pipelines and Steps](#pipelines-and-steps)
- [Parsing and ingest](#parsing-and-ingest)

## Pipelines and Steps

Design and internals: [Pipelines and Steps architecture](architecture.md).

| Route | Purpose |
|---|---|
| `GET /pipelines`, `/pipelines/combined`, `/pipelines/authored`, `/pipelines/authored/{id}` | lists and one Pipeline |
| `GET /pipelines/node-types`, `GET /pipelines/processor-catalog` | palette and node specs |
| `GET /pipelines/{name}/graph`, `PUT /pipelines/{name}/graph`, `GET …/graph/raw`, `GET …/document` | read and save the graph |
| `POST /pipelines/authored/{id}/dry-run`, `/run`, `/trigger` | dry run, test run and trigger |
| `GET /pipelines/{name}/history`, `…/history/{version}`, `…/history/diff`, `POST …/history/{version}/restore` | saved versions |
| `POST /pipelines/{name}/rename`, `GET` / `POST …/settings`, `POST …/label`, `POST …/save-as-template` | rename, settings, template |
| `GET /pipelines/{name}/related`, `GET /config/pipeline/{name}/impact` | dependants and impact |

## Parsing and ingest

Design and internals: [Parsing and ingest architecture](architecture.md).

| Route | Purpose |
|---|---|
| `GET /parsers`, `POST /parsers/{id}/preview`, `/parsers/asn1/profile` | parser list and compute-only preview |
| `POST /config/preview/parsing`, `POST /config/preview/schema` | preview the parse and the cast of a draft |
| `GET /runs/{name}/batches`, `/files`, `/lineage`, `/quarantine`, `/commits`, `/outputs` | Run-detail ledgers |
| `GET /runs/{name}/errors?file=` | the reject rows for one file (bounded, reports `truncated`) |
| `POST /runs/{name}/trigger`, `/pause`, `/resume`, `/reprocess`, `/replay-rejects` | operator actions |
