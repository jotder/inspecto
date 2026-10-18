---
type: Feature
title: "Parsing and ingest — screens"
description: "Where Parsing and ingest lives in the UI and what each screen does."
tags: [etl, parsing-ingest, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Parsing and ingest — screens

Screens for Parsing and ingest. Design and internals: [Parsing and ingest architecture](architecture-parsing-ingest.md).

- **Pipeline editor, Parse definition** (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-parse-definition.component.ts`):
  frontend choice, grammar form rendered from the parser's served `grammarSchema`, and a sample preview.
- **Run detail** (`inspecto-ui/src/app/modules/admin/run-detail/run-detail.component.ts`): tabs
  Batches, Files, Lineage, Quarantine and Commits, plus "View the rejected rows".
- **Processing status** (`inspecto-ui/src/app/modules/admin/processing-status/processing-status.component.ts`):
  problem files per Pipeline.
