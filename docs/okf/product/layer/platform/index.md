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
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Architecture work — MoSCoW analysis (2026-11-12)](plan-architecture-moscow.md) · [layer.platform - board row detail](plan-board-detail.md) · [Compliance (CMP) — capability spec](plan-compliance.md) · [Data plane (DAT)](plan-data-plane.md) · [Editions & packaging (PKG) — capability spec](plan-editions.md) · [Inspecto — Forward Roadmap](plan-roadmap.md) · [Guards & repository tooling (TOOL) — capability spec](plan-tooling.md) |
| spec | current | [Branching & Release Policy](spec-branching-release.md) · [Build & Test](spec-build-test.md) · [Editions — engineering specification (Basic / Standard / Advanced / Preview)](spec-editions.md) · [Module reorganisation - decision record (D-MR1..D-MR12)](spec-module-reorg-decisions.md) · [Module reorganisation - traps and gotchas](spec-module-reorg-gotchas.md) · [Product Map — the commercial tree, its guard, its views and customer builds (as built)](spec-product-map.md) · [Work tracking — one board, tagged rows, generated hub views](spec-work-tracking.md) |
| architecture | current | [Editions](architecture-editions.md) · [Modules](architecture-modules.md) · [Platform Services](architecture-platform-services.md) · [Inspecto platform architecture](architecture-platform.md) |
| features | current | [Platform — features](features-platform.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Platform — the builder kit](user-guide-builder-kit.md) · [Platform — user guide](user-guide-platform.md) · [Tutorial: run it — install, sign-in, backup, upgrade and audit](user-guide-tutorial-run-the-platform.md) · [Tutorial: ship it — from a Space to an installed App](user-guide-tutorial-ship-an-app.md) |
| admin-guide | current | [Platform — installing Apps on a running server](admin-guide-apps.md) · [Platform — container image, Helm chart and air-gapped install](admin-guide-container-airgap.md) · [Platform — the deploy kit: reverse proxy, preflight, upgrade, bundle verify and off-site backup](admin-guide-deploy-kit.md) · [Platform — entitlements](admin-guide-entitlements.md) · [Cross-Cutting Gotchas](admin-guide-gotchas.md) · [HA/DR drill runbook: two-node active/passive on plain Linux (T4)](admin-guide-ha-dr-drill.md) · [Operations & Launch Flags](admin-guide-launch-flags.md) · [Local feature testing without a real IAM or Postgres](admin-guide-local-testing.md) · [Operations reference — utilities, batching, output, deployment](admin-guide-operations-reference.md) · [Inspecto — Operations & Support Guide (pointer map)](admin-guide-operations.md) · [Troubleshooting](admin-guide-troubleshooting.md) · [UAT Sample-Data Seeding Runbook](admin-guide-uat-seeding.md) |
| api | current | [DuckLake & the warehouse query layer](api-integrations-ducklake.md) |
| tests | current | [Doc claim audit — 2026-10-19 (PRODUCT-DOCS-HUB-1)](tests-doc-claim-audit-2026-10-19.md) · [Guards that cannot fail](tests-guard-coverage.md) · [Operator mutation checklist](tests-operator-mutation-checklist.md) · [Performance assessment & bottleneck analysis](tests-performance.md) · [Provenance conservation — live-data verification protocol (OPS-5)](tests-provenance-conservation.md) · [Java Review Coverage](tests-review-coverage.md) · [Inspecto — Testing Guide](tests-testing-guide.md) · [Platform tooling — tests inventory](tests-tools-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `layer.platform` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `NATIVE-LICENCE-LINUX-MACOS-1` | P3 | chore | Two licence calls on the tokenizers natives wait on the operator. | operator |
| `AGENT-KIT-1` | P2 | feature | The Agent Kit: AI agents build, ship and manage Apps on a running Inspecto safely, through an optional MCP module. | — |
| `RELEASE-PIPELINE-UNRUN-1` | P2 | chore | No release can be cut today: release.yml has never run and its secrets and scan DB are not configured. | operator |
| `DEPLOY-EXPOSURE-KIT-1` | P3 | chore | Fence /metrics and /health/details beyond the network, then build the deploy kit the docs assume. | — |
| `PRODUCT-ASSEMBLY-1` | P2 | feature | Assemble finished domain Products and the Platform with chosen Add-ons from definition files, with one tool. | — |
| `CONTAINER-AIRGAP-PROOFS-1` | P3 | chore | Run a Kubernetes install of a Product's chart and a complete air-gapped bundle, the proofs that close PA-8. | demand |
| `APP-KIT-ALERT-RULE-1` | P3 | feature | the App kit's starter (tools/inspecto-app.mjs new) ships no Alert Rule, so a partner App made with it raises no Alert from its findings u… | demand |
| `ASSURE-OPERABILITY-1` | P2 | feature | The two-host HA/DR drill, the offline vulnerability scan snapshot and tightened nightly thresholds remain. | operator |
| `DEPLOY-SERVICE-WRAPPER-1` | P2 | chore | Run the service wrappers' live acceptance (kill -9 recovery and the reboot leg) on Windows and Linux. | — |
| — | P2 | feature | Postgres multi-user | operator |
| `TOOL-TESTS-UNWIRED-BASELINE-1` | P3 | chore | 46 of 112 tools/*.test.mjs are named by no workflow, so no gate runs them. | demand |
| `TEST-ENV-PG-ASN-UNRUN-1` | P3 | chore | Two test groups (Postgres and asn-*) cannot run on the shared User profile, so a green reactor does not cover them. | operator |
| `REALTIME-DECISIONING-1` | P3 | feature | The rest of the Real-time Detection Add-on and the CEP App (card authorisation, pre-call blocking). | demand |
| `REALTIME-INLINE-DECISION-1` | P3 | feature | inline allow / block decision on each record of a stream | demand |
| `SCALEOUT-HELM-NPOD-1` | P2 | feature | The only Helm chart is a replicas-1 StatefulSet: no N-pod topology, ingress template or per-pod Space assignment. | operator |
| `SCALEOUT-D14-D16-DECISIONS-1` | P2 | chore | The scale-out decisions D14, D15 and D16 and the rows they unblock. | operator |
| `SCALEOUT-LIVE-HALVES-1` | P3 | chore | The live halves of spike S5 and an end-to-end S1 run across N processes; D13 has no code. | operator |
| `MODULE-REORG-P4-3` | P3 | feature | A disabled module's Link Analysis graph runs and index builds in flight run to their end. | — |
| `MODULE-REORG-P3-THIN-JARS` | P3 | feature | Thin per-module jars and an Offering-driven launcher classpath. | — |
| `CODEGRAPH-AFFECTED-UNUSABLE-1` | P3 | chore | Re-drive codegraph affected on any codegraph upgrade before relaxing the guidance. | — |
| `PRODUCT-MAP-S6-1` | P3 | feature | Product Map slice S6, on demand: | demand |
| `CUSTOMER-BUILD-DOMAIN-APPS-1` | P3 | feature | A Domain product customer must hold every optional App the composed Space Template joins, or the customer build is refused. | demand |
| `PRODUCT-MAP-APP-EDITION-PRICES-1` | P3 | feature | an App has one Order Code (INS-APP-<ID>), so a price per App × Edition (D-PA6) has no key yet. | demand |
| `INFO-RESTRUCTURE-1` | P2 | chore | The information base is restructured and kept right: one fact one home, current state only, pages an agent can read whole. | — |
| `USER-HANDBOOK-1` | P2 | feature | The Handbook: users learn to build, ship and manage an App from the console's Help, organised by lifecycle for three tracks. | — |
