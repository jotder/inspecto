---
name: docs-lookup
description: >
  Find anything in the Inspecto docs fast. Trigger on "where is X documented", "how does X work",
  "find the spec/guide/api for X", or before reading/writing any doc. Maps a question to a Product
  Map Item id (product-map/) plus a slot, reads the generated hub index.md, then the slot file.
  Encodes the slot table, the layer/platform vs layer/product rule, and where NOT to look.
---

# docs-lookup — one hub per Product Map Item

Every current doc lives in **one hub per Product Map Item**: `docs/okf/product/<kind>/<name>/`, mirroring
`product-map/<kind>/<name>.toon` (PRODUCT-DOCS-HUB-1, 2026-10-19). Kinds: `module`, `addon`, `layer`,
`edition`, `solution-pack`. All hubs are listed in `docs/okf/product/index.md`.

## Procedure

1. **Name the item.** Translate the question into a Product Map Item id (`module.etl`, `addon.incidents`,
   `layer.platform`…). `ls product-map/<kind>/` lists ids; each `.toon` has `id`, `title`, `parent`, `docs:`.
   Not sure? `grep -ril "<term>" product-map/` — the `title` lines usually match.
   ⚠ Module names are kebab-case (`module/case-management`), add-on names camelCase
   (`addon/caseManagement`). An add-on hub is often a thin index pointing at its module hubs.
2. **Read the hub `index.md`** — `docs/okf/product/<kind>/<name>/index.md`. It is **generated**
   (`node tools/render-product-docs.mjs`): never hand-edit it.
3. **Pick the slot** from the table and read `<slot>.md` / `<slot>-<topic>.md`.
4. Still missing? `grep -rl "<symbol>" docs/okf/product/` — then the root canon, then the HTTP
   contract `docs/api/openapi-v1.json` for exact routes. For code, `codegraph_explore` first.

## Slot table (file name = slot)

| Slot | Answers | Example |
|---|---|---|
| `plan` | why / decisions / roadmap of the item | `docs/okf/product/module/etl/plan-pipeline-authoring.md` |
| `spec` | exact behaviour, contracts, config keys | `docs/okf/product/module/config/spec-config-safety.md` |
| `architecture` | how it is built, seams, classes | `docs/okf/product/module/engine/architecture-pipeline-graph.md` |
| `features` | what it can do (feature list) | — |
| `feature-db` | feature rows / inventory data | — |
| `user-guide` | how a user does a task in the UI | `docs/okf/product/module/etl/user-guide-pipelines.md` |
| `admin-guide` | operate, configure, deploy, flags | `docs/okf/product/layer/platform/admin-guide-launch-flags.md` |
| `api` | HTTP / integration surface | `docs/okf/product/layer/platform/api-integrations-ducklake.md` |
| `tests` | test strategy, coverage, QA scripts | `docs/okf/product/layer/platform/tests-testing-guide.md` |
| `brochure` | sales / whitepaper text | `docs/okf/product/module/la-core/brochure-whitepaper.md` |

## Which layer hub?

- **`layer/platform`** — platform-wide **backend** docs: build/test, launch flags, operations,
  troubleshooting (`admin-guide-troubleshooting.md`), gotchas, editions model, reactor.
- **`layer/product`** — **UI-wide** docs and **whole-product sales** material.
- A doc about one module or add-on goes in **that item's** hub, never a layer hub.

## Root canon (outside the hubs)

`docs/GLOSSARY.md` (binding vocabulary — check before naming anything) · `docs/INDEX.md` (map) ·
`docs/BACKLOG.md` (open work) · `docs/REQUIREMENTS.md` · `docs/EDITIONS.md` · `docs/USER_GUIDE.md` ·
`docs/api/` (HTTP contract) · `docs/superpower/` (in-flight plans only).

## Don't

- Don't read `docs/archived-documents/` as current — it is history.
- Don't trust pre-2026-10-19 paths (`okf/backend`, `okf/frontend`, `stakeholders`, `ops`, `ui` trees) — gone.
- Don't create a doc outside a hub, or a new root `docs/*.md`. When adding or retiring a doc, update
  `docs/INDEX.md` in the same change and re-run `node tools/render-product-docs.mjs`.
- Guards after doc edits: `node tools/check-doc-links.mjs`, `node tools/check-doc-citations.mjs`,
  `node tools/check-vocabulary.mjs`, `node tools/check-product-docs.mjs`.
