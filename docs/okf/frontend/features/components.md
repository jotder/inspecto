---
type: Feature
title: Components Registry
description: The reusable-component registry — grammar, schema, transform, sink (and rule) definitions.
resource: inspecto-ui/src/app/modules/admin/components/components.routes.ts
tags: [feature, components, settings, registry]
timestamp: 2026-06-28T00:00:00Z
---

# Components Registry

Route under the Settings nav group. Manages reusable component definitions via `ComponentsService` —
`COMPONENT_TYPES` = `grammar` · `schema` · `mapping` · `transform` · `sink` (the editable palette; the `ComponentType` union is wider), plus `rule`
(used by the data-table [rule](../design-system/rule.md) save, but intentionally **not** in the palette).
Grammars are created/edited from the [Pipelines](pipeline-editor.md) `GrammarEditorDialog`, or Onboarding's
Parsing stage — one shared surface, see [Grammar configuration](grammar-config.md). *(The offline mock store this page used to mention was deleted 2026-08-31.)* ⚠ The pane sends no `If-Match`
(last-write-wins) and offers no `force` on a `409` delete — see the [MET capability spec](../../capabilities/metamodel/metamodel.md) §3.9.
