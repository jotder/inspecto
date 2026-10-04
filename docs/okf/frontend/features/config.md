---
type: Feature
title: Config
description: The TOON configuration view for the active space.
resource: inspecto-ui/src/app/modules/admin/config/config.routes.ts
tags: [feature, config, settings, toon]
timestamp: 2026-06-28T00:00:00Z
---

# Config

Route `/config` (Settings nav group). Views/edits the TOON configuration for the active
[space](../conventions/multi-space.md). Backed by `ConfigService`. Secrets are references only (`${ENV:…}`) and
never round-tripped raw — see [API & data](../conventions/api-and-data.md).

The pane authors a *draft for manual commit* (copy the assembled `.toon`) — it never persists by name, so
the dup-guard/name-at-save form rules don't apply. The dynamic field grid is `<inspecto-schema-form>` over `AttributeSpec[]` mapped from `FieldSpec[]`
(the port `frontend/log.md` records; this paragraph called it "still open" until 2026-09-08).

## Preview tab (2026-10-04, `API-DEAD-METHODS-1`)

The pane's third tab — *Author draft · Validate file · Preview* — is `config/config-preview.component.ts`
(`<app-config-preview>`; input parsing is framework-free in `config-preview.ts`). It is the one home for the three
stateless, scratch-only draft previews, which had no caller until now; nothing is saved. The author picks a
preview, pastes a **draft config (JSON object)** and a **sample**, and runs it:

| Preview | `ConfigService` method → route | Sample | Shows |
|---|---|---|---|
| Parsing | `previewParsing` → `POST /config/preview/parsing` | raw text | frontend badge, rows parsed / rejected counts, the parsed rows |
| Schema | `previewSchema` → `POST /config/preview/schema` | JSON rows | rows that cast / rejected counts, the **mapped output** (only when the draft carries mapping rules), the rejected rows |
| Enrichment | `previewEnrichment` → `POST /enrichment/preview` | JSON rows | the transform's rows, with a notice when the result was cut |

- **Why Config, not a new route.** It is the surface already about *drafts* (Author draft assembles one, Validate
  checks it) and Settings dual-hosts it, so no nav entry or lazy route was needed. The Pipelines Parse pane holds a
  *graph*, not a draft config — which is why it never called `previewParsing`.
- **Failure states.** Bad JSON or an empty sample is refused client-side with an alert and no request; a 422 shows
  the server's own reason; a **403** on the Enrichment preview (it executes the draft's SQL, gated
  `canAuthorWorkbench`) reads as "needs Workbench authoring". Running shows a skeleton; nothing yet shows the
  shared empty state.
- **Deliberately not done.** The parsing response's `columnTypes` / `resolved` extras are not rendered; the draft
  box is raw JSON (a typed form per draft kind would duplicate the schema editors).
