---
type: ProductItem
title: Config model and codecs
item: module.config
description: Documentation hub for module.config — every document about it, by slot.
tags: [product-item, module]
---

# Config model and codecs (`module.config`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.config`](../../../../../product-map/module/config.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.config - board row detail](plan-board-detail.md) · [Component metamodel & Catalog (MET)](plan-metamodel.md) · [Space evolution — a Space the server understands as a whole, reached without breaking one](plan-space-evolution.md) · [Spaces & tenancy (SPC) — Anomaly Model examples, bundles, decisions and verification](plan-spaces-bundles-decisions.md) · [Spaces & tenancy (SPC) — Purpose, requirements and the Space model](plan-spaces-scope-model.md) · [Spaces & tenancy (SPC) — Specification — Space Templates (continued)](plan-spaces-templates-continued.md) · [Spaces & tenancy (SPC) — Specification — Space Templates](plan-spaces-templates.md) · [Spaces & tenancy (SPC)](plan-spaces.md) |
| spec | current | [Column contract — Schema column changes and what they break (SE-3a, SE-3b)](spec-column-contract.md) · [Config and TOON - gotchas](spec-config-gotchas.md) · [Config Safety Validator — Job config decisions, the accepted-key census and the classification vocabulary](spec-config-safety-keys-vocabulary.md) · [Config Safety Validator — Maker-checker and Safety Policy tier files](spec-config-safety-maker-checker.md) · [Config Safety Validator — Path containment and the config write routes](spec-config-safety-path-containment.md) · [Config Safety Validator — Allowed roots, data refs and the one save gate](spec-config-safety-roots-save-gate.md) · [Config Safety Validator](spec-config-safety.md) · [Pipeline config keys — the single block census](spec-pipeline-config-keys.md) · [Space data layout — config in git, data outside it](spec-space-data-layout.md) · [Space lock — the server's signed record of a Space (SE-1a)](spec-space-lock.md) · [Space origin — which template made a Space (SE-5a)](spec-space-origin.md) · [Space promotion — a whole Space applied to an existing Space as a previewed change set (SE-6b)](spec-space-promotion.md) · [Space tests — cases that travel with a Space (SE-6a)](spec-space-tests.md) · [TOON Configuration](spec-toon-config.md) |
| architecture | current | [Spaces](architecture-config.md) · [Space anatomy — every folder and file in a Space](architecture-space-anatomy.md) |
| features | current | [Spaces — features](features-spaces.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Space Template](user-guide-concept-space-template.md) · [Space](user-guide-concept-space.md) · [Config](user-guide-config.md) · [Grammar configuration (the one surface)](user-guide-grammar-config.md) · [Spaces — screens](user-guide-spaces.md) · [Tutorial 1 — Your first Space and Dataset](user-guide-tutorial-first-space.md) |
| admin-guide | current | [Configuration reference — The collector block, type mapping and the Postgres state store](admin-guide-configuration-reference-collector-types.md) · [Configuration reference — Configuration reference and Spaces](admin-guide-configuration-reference-spaces.md) · [Configuration reference](admin-guide-configuration-reference.md) |
| api | current | [Component shape reference](api-component-shapes.md) · [Spaces — API](api-spaces.md) |
| tests | current | [Config model and codecs — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.config` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `IMPORT-LOADER-NAMES-UNRESERVED-1` | P3 | defect | ImportLoaderInventoryTest.everyFixedNameALoaderReadsIsReservedOrAllowedWithAReason fails on master: two fixed names a loader reads — even… | — |
| `UPLOAD-DEFAULTS-1` | P3 | defect | upload defaults, remainder: | — |
| `SPACE-CREATE-NAME-1` | P3 | defect | creating a Space ignores name and displayName: | — |
| `TEMPLATE-GOVERNANCE-1` | P3 | feature | a Space Template carries no governance: | — |
| `SPACE-COHERENCE-1` | P2 | feature | Space config at agent speed: one link graph behind every edit, agents strict first. | — |
| — | P2 | feature | SPACE-LINK-CENSUS-1 the link census: the default flip and the plan scope not built | — |
| — | P3 | defect | COMPONENT-DELETE-IN-USE-WORDS-1 the Components screen words every refused delete as "referenced by a pipeline" | — |
| — | P3 | feature | SPACE-EVOLUTION-1 the release-gated rest of the Space evolution | operator |
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `SIGNAL-BOARD-1` | P2 | feature | One clear meaning for Signal, and a board Jobs can trust. | — |
| `UNKNOWN-KEYS-REJECT-1` | P3 | defect | reject unknown Dataset and Reconciliation keys for people too: | operator |
| `COMPONENT-VALIDATE-ALL-1` | P3 | feature | a dry run (executing preview) for a KPI, Reconciliation, Dataset, Widget or Dashboard: | — |
| `BUILDER-API-PAPERCUTS-1` | P3 | defect | small gaps that cost an agent calls: | — |
| `SPACE-TO-APP-CONSOLE-1` | P3 | feature | Turn a working Space into a Space Template, and into an App, from the console. | — |
| `APPS-UPGRADE-CONSOLE-1` | P3 | feature | Apps, a Space's template upgrade and the audit-chain check have no console: | — |
| `SPACE-EXPORT-CLEAN-1` | P3 | feature | a Space is not cleanly exportable as a Space Template: | — |
| `ZERO-SPACE-BOOTSTRAP-RED-1` | P2 | defect | With no Space attached, bootstrap now answers CAPABILITY_UNAVAILABLE ("No Space is attached …") where four tests expect a clean zero-Spac… | — |
| `APPROVAL-POLICY-PRESET-1` | P3 | feature | a Standard install is not gated by default: | — |
| `CONTROLAPI-DATA-DIR-MISMATCH-1` | P2 | defect | The control plane and the engine disagree on a Space's data root when -Ddata.dir is set: | — |
| `PENDING-CHANGE-SECRET-READBACK-1` | P3 | defect | a Job's secret parameter can be read back through its Pending Change: | — |
| `ORIGIN-UPGRADE-UNHELD-1` | P3 | defect | a Space Template upgrade that adds and updates nothing rewrites the Space origin record without holdSet, even under an approval policy. | — |
| `SPACE-ROOTED-DATA-ROOT-MIGRATION-1` | P3 | feature | An install whose data_root is rooted or absent is still not relocatable, and no route migrates it. | demand |
| `LA-INDEX-UNDER-DATA-ROOT-1` | P3 | feature | The Link Analysis index is runtime data but lives in the Space's config folder: | demand |
| `SPACES-CONSOLIDATION-1` | P3 | chore | Space config is in git, its data is in C:\sandbox\inspecto_spaces\<id>: | operator |
| `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | P2 | feature | The shapes the object-store ingest lane still refuses (sinks:, produces: reference, the readers). | operator |
| `COMPONENT-REFERENCE-GOVERNANCE-KINDS-1` | P3 | feature | the shape reference for the four governance component kinds: | demand |
