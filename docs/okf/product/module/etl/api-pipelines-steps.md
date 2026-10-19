---
type: Reference
title: "Pipelines and Steps — API"
description: "Control-plane routes for Pipelines and Steps, with their gates."
tags: [etl, pipelines-steps, api]
timestamp: 2026-10-19T00:00:00Z
---

# Pipelines and Steps — API

Routes for Pipelines and Steps. Design and internals: [Pipelines and Steps architecture](architecture-pipelines-steps.md).

| Route | Purpose |
|---|---|
| `GET /pipelines`, `/pipelines/combined`, `/pipelines/authored`, `/pipelines/authored/{id}` | lists and one Pipeline |
| `GET /pipelines/node-types`, `GET /pipelines/processor-catalog` | palette and node specs |
| `GET /pipelines/{name}/graph`, `PUT /pipelines/{name}/graph`, `GET …/graph/raw`, `GET …/document` | read and save the graph |
| `POST /pipelines/authored/{id}/dry-run`, `/run`, `/trigger` | dry run, test run and trigger |
| `GET /pipelines/{name}/history`, `…/history/{version}`, `…/history/diff`, `POST …/history/{version}/restore` | saved versions |
| `POST /pipelines/{name}/rename`, `GET` / `POST …/settings`, `POST …/label`, `POST …/save-as-template` | rename, settings, template |
| `GET /pipelines/{name}/related`, `GET /config/pipeline/{name}/impact` | dependants and impact |
