---
type: ProductItem
title: Platform
item: layer.platform
description: Documentation hub for layer.platform — every document about it, by slot.
tags: [product-item, layer]
---

# Platform (`layer.platform`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`layer.platform`](../../../../../product-map/layer/platform.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Compliance (CMP) — capability spec](plan-compliance.md) · [Data plane (DAT)](plan-data-plane.md) · [Editions & packaging (PKG) — capability spec](plan-editions.md) · [Inspecto — Forward Roadmap](plan-roadmap.md) · [Guards & repository tooling (TOOL) — capability spec](plan-tooling.md) |
| spec | current | [Branching & Release Policy](spec-branching-release.md) · [Build & Test](spec-build-test.md) · [Module reorganisation - decision record (D-MR1..D-MR12)](spec-module-reorg-decisions.md) · [Module reorganisation - traps and gotchas](spec-module-reorg-gotchas.md) · [Work tracking — one board, tagged rows, generated hub views](spec-work-tracking.md) |
| architecture | current | [Log](architecture-change-log.md) · [Editions Model](architecture-editions-model.md) · [Architecture layers — platform-wide design map](architecture-layers.md) · [Inspecto as a Living Operational System](architecture-living-system.md) · [Module taxonomy — axes, offerings, gates (target model)](architecture-module-taxonomy.md) · [Inspecto Backend Overview](architecture-overview.md) · [Platform Services](architecture-platform-services.md) · [Maven reactor & modularization (as-built)](architecture-reactor.md) · [Inspecto — Technical Architecture](architecture-technical.md) · [Backend Architecture](architecture.md) |
| features | current | [Platform — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Platform — user guide](user-guide.md) |
| admin-guide | current | [Cross-Cutting Gotchas](admin-guide-gotchas.md) · [HA/DR drill runbook: two-node active/passive on plain Linux (T4)](admin-guide-ha-dr-drill.md) · [Operations & Launch Flags](admin-guide-launch-flags.md) · [Local feature testing without a real IAM or Postgres](admin-guide-local-testing.md) · [Operations reference — utilities, batching, output, deployment](admin-guide-operations-reference.md) · [Inspecto — Operations & Support Guide (pointer map)](admin-guide-operations.md) · [Troubleshooting](admin-guide-troubleshooting.md) · [UAT Sample-Data Seeding Runbook](admin-guide-uat-seeding.md) |
| api | current | [DuckLake & the warehouse query layer](api-integrations-ducklake.md) |
| tests | current | [Guards that cannot fail](tests-guard-coverage.md) · [Operator mutation checklist](tests-operator-mutation-checklist.md) · [Performance assessment & bottleneck analysis](tests-performance.md) · [Provenance conservation — live-data verification protocol (OPS-5)](tests-provenance-conservation.md) · [Java Review Coverage](tests-review-coverage.md) · [Inspecto — Testing Guide](tests-testing-guide.md) · [Platform tooling — tests inventory](tests-tools-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `layer.platform` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `NATIVE-LICENCE-LINUX-MACOS-1` | P3 | chore | Linux / macOS native licences are checked by hash, but not exercised or fully verified. | — |
| `ASSURE-OPERABILITY-1` | P2 | feature | the generic operability half (WS-01/02/04/26, wave 4.5 of superpower/assurance-capability-plan.md). | operator |
| `DEPLOY-SERVICE-WRAPPER-1` | P2 | chore | the live acceptance is UNRUN. | — |
| — | P2 | feature | Postgres multi-user | — |
| `TEST-ENV-PG-ASN-UNRUN-1` | P3 | chore | Two test groups cannot run on the shared User profile, so a green reactor does not cover them. | — |
| `REALTIME-DECISIONING-1` | P3 | feature | Real-time Decisioning add-on | — |
| `PREDICTIVE-ANALYTICS-1` | P3 | feature | Predictive Analytics add-on | — |
| `SCALEOUT-HELM-NPOD-1` | P2 | feature | The only Helm chart is a replicas-1 StatefulSet; there is no N-pod topology, no ingress template, no per-pod Space assignment. | operator |
| `SCALEOUT-D14-D16-DECISIONS-1` | P2 | chore | Three operator decisions are unsigned and block the rest of phase C. | operator |
| `SCALEOUT-LIVE-HALVES-1` | P3 | chore | The live halves of spike S5 (pg_duckdb view queried from psql) and of an end-to-end S1 run across N processes are still owed; D13 has no… | — |
| `MODULE-REORG-P4-3` | P3 | feature | a disabled module's in-flight work and long-lived subscriptions (the only P4 residue). | — |
| `MODULE-REORG-P3-THIN-JARS` | P3 | feature | thin per-module jars + Offering-driven launcher classpath. | — |
| `CODEGRAPH-AFFECTED-UNUSABLE-1` | P3 | chore | codegraph affected over-reports to uselessness; only the upstream defect is open. | external |
