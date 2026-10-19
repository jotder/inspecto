---
type: Pointer
title: Pipelines (Authoring) — MOVED
description: Superseded pointer — the editor concept now lives in user-guide-pipeline-editor.md.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipelines.routes.ts
tags: [pointer, pipelines]
timestamp: 2026-09-01T00:00:00Z
---

# Moved

This concept was consolidated into **[Pipeline Editor](user-guide-pipeline-editor.md)** (2026-09-01
docs-consolidation pass, pipeline-editor pilot). The dated change-log form of this file is in git
history (`git log -- docs/okf/product/module/etl/user-guide-pipelines.md`); the plans it distilled are in
`docs/archived-documents/plans-archive/`.

## Screens

Screens for Pipelines and Steps. Design and internals: [Pipelines and Steps architecture](architecture-pipelines-steps.md).

- **Pipeline editor** (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-editor.component.ts`,
  graph in `pipeline-editor-graph.component.ts`): palette, node forms generated from the served
  attribute specs, dry run, save and history. UI reference:
  [pipeline-editor](user-guide-pipeline-editor.md).
- **Runs and Run detail**: trigger, pause, resume, drain ([parsing-ingest](architecture-parsing-ingest.md) §6).
