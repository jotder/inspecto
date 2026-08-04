---
type: Feature
title: Collectors
description: Configured collection tasks, listed in a standard data-table.
resource: inspecto-ui/src/app/modules/admin/collectors/collectors.routes.ts
tags: [feature, collectors, acquisition]
timestamp: 2026-08-04T00:00:00Z
---

# Collectors

> Configuring what a collector *does* is a different concept: see
> [Collector configuration](collector-config.md) for the one shared surface, spec table and write
> route behind the Onboarding Collection stage and the Pipelines `acquisition` node.

Route `/collectors` (Workbench nav group). Lists configured collection tasks in a **standard**
[data-table](../design-system/data-table.md) (`autoHeight`). Backed by `CollectorsService`.

*(Renamed from "Sources" per the Source→Collector glossary flip, GLOSSARY §2/§3. <!-- vocab-allow: names the rename itself -->
The route, folder and service had already moved; this doc had not — it still claimed `/sources`,
`SourcesService` and an "Acquisition" nav group.)*
