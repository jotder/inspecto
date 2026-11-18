---
type: Reference
title: "Collectors, parsing, ingest and Pipelines — API"
description: "Control-plane routes for Collectors and Connections, authoring Pipelines and Steps, previewing parsers, and reading and acting on Runs, with their gates."
tags: [elt, acquire, collectors, pipelines-steps, parsing-ingest, api]
timestamp: 2026-11-18T00:00:00Z
---

# Collectors, parsing, ingest and Pipelines — API

**What this covers.** The HTTP routes an operator or an author calls along the whole ELT road: managing
Connections and Collectors and nudging a Collector to scan, previewing parsers and reading the Run-detail
ledgers and actions, and listing and saving Pipelines, the Step palette, dry runs and test runs, and saved
versions. It is the route index only; how each route behaves is in the [user guide](user-guide.md) and the
design in [architecture](architecture.md).

**Contents**

- [Collectors and Connections](#collectors-and-connections)
- [Parsing and ingest](#parsing-and-ingest)
- [Pipelines and Steps](#pipelines-and-steps)

## Collectors and Connections

Design and internals: [Collectors architecture](architecture.md).

From `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` and `ConnectionRoutes.java`:

| Route | Gate |
|---|---|
| `GET /collectors` | read |
| `POST /collectors/{id}/notify` | `canOperateRuns`; body ignored, triggers a scan; audited `collector.notified` |
| `GET /metrics/acquisition` | read |
| `GET /connections`, `GET /connections/{id}` | read |
| `POST /connections`, `PUT /connections/{id}`, `DELETE /connections/{id}` | `canOnboardConnections` |
| `POST /connections/{id}/test`, `POST /connections/test`, `POST /connections/{id}/probe` | `canOnboardConnections` |
| `GET /connections/{id}/explore`, `GET /connections/{id}/sample` | read |

Gates are listed in `platform/inspecto-access/src/main/java/com/gamma/access/CapabilityManifest.java`.

## Parsing and ingest

Design and internals: [Parsing and ingest architecture](architecture.md).

| Route | Purpose |
|---|---|
| `GET /parsers`, `POST /parsers/{id}/preview`, `/parsers/asn1/profile` | parser list and compute-only preview |
| `POST /config/preview/parsing`, `POST /config/preview/schema` | preview the parse and the cast of a draft |
| `GET /runs/{name}/batches`, `/files`, `/lineage`, `/quarantine`, `/commits`, `/outputs` | Run-detail ledgers |
| `GET /runs/{name}/errors?file=` | the reject rows for one file (bounded, reports `truncated`) |
| `POST /runs/{name}/trigger`, `/pause`, `/resume`, `/reprocess`, `/replay-rejects` | operator actions |

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
