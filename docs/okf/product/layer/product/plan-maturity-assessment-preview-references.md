---
type: Plan
title: Product maturity assessment — every client-facing item — Per-item assessments — Preview edition, and references
description: Part 8 of 8 of Product maturity assessment — every client-facing item: References
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
item: layer.product
audience: internal
status: plan
verified: 2026-11-19 last-edit
---

# Product maturity assessment — every client-facing item — Per-item assessments — Preview edition, and references

Part 8 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · previous: [Per-item assessments — Link Analysis packs, Inspecto LA and the editions](plan-maturity-assessment-la-editions.md).

## 6. Per-item assessments (continued)

#### edition.preview — Preview
- **What it is (customer view):** an evaluation build that carries every module (today the same set as Advanced) with demo sign-in, for presales demos and design partners.
- **Scores:** F 4 · B 3 · Q 3 · E 2 · R 1 → **Level L2 (Prototype)**. It should not be a SKU at all.
- **Evidence:**
  - The product map makes it a priced GA SKU: `billable: true, orderCode INS-ED-PREVIEW, billing per-node, status ga` (`product-map/edition/preview.toon`).
  - The binding doc forbids that: "Preview — not a customer-facing tier… Never ship Preview to a customer, never advertise it externally… carries no compliance-scope guarantee at all" (`docs/EDITIONS.md` "Preview — not a customer-facing tier").
  - Its own brochure, written for customers, says "evaluation and incubation build, not a production tier" (`edition/preview/brochure-preview.md`). No BACKLOG row tracks this contradiction.
  - `release.yml` packages Basic, Standard and Advanced only (lines 189, 244, 262), so Preview has no released artifact.
  - Posture differs from Advanced (`objectsBackend default` vs `postgres`, `offerings/preview.toon`), so "identical to Advanced" is true of modules, not of posture.
  - Guards: `tools/offering-classpath.test.mjs` "Preview carries every module…" and `tools/check-offerings.mjs`. The `pom.xml` `edition-preview` profile "does not auto-union" (`docs/EDITIONS.md`), which is a manual drift risk.
  - `offerings/preview.toon` lists no content packs, although the brochure says "All solution packs, including the Link Analysis showcases" (`OFFERING-DECLARATION-DRIFT-1` D2).
- **Strengths vs market:** one build shows the whole product with ready personas. That is a strong presales vehicle comparable to vendors' sandbox/trial environments (general market).
- **Gaps vs market / RFP risk:**
  - A priced, GA order code for a build the platform doc says must never reach a customer is a commercial and compliance risk: no row (`OFFERING-DECLARATION-DRIFT-1` covers only the pack listing).
  - Demo sign-in only, with no compliance scope, so it cannot answer security RFP lines.
- **RFP stance on the 5 most-asked requirements (evaluation / trial edition):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Full-feature trial | Comply | every module; demo bundles rehearsed live |
| Seeded demo content | Partial | showcases ship in the demo zip; not listed in the Offering (D2) |
| Production use | Not offered | `docs/EDITIONS.md` forbids it |
| Security / compliance scope | Not offered | "no compliance-scope guarantee" |
| Signed, released artifact | Not offered | not in `release.yml` |
- **To reach the next level:** set `billable: false, status: preview` (or drop the order code) in `product-map/edition/preview.toon`; close D2 by listing the showcases; add a check that the `edition-preview` pom profile equals the module table.

#### Edition ladder — defensibility of Basic / Standard / Advanced (/ Preview) as packaging
- **What it is (customer view):** three nested builds sold per node, differing by who uses them (one analyst, a team, an organisation), how much data and how much governance. Preview is an evaluation build.
- **Scores:** F 3 · B 4 · Q 4 · E 2 · R 3 → **Level L2 (Prototype)** *(calibrated 2026-10-20: E 2 caps the level; the mechanism alone is L3)* as a mechanism. The commercial enforcement is L2.
- **Evidence:**
  - Mechanism: build flavors with no branches. Absent modules answer 503 with an explanation (never 404), `/bootstrap` flags derive from registered routes, and each gated cell has a falsification test (`layer/platform/architecture-editions.md` recipe items 1–7, 28; 20+ edition test classes listed above).
  - Offering == bundle == pom profile is enforced in CI (`tools/check-offerings.mjs`, `tools/offering-classpath.mjs`, `tools/bundle-modules.mjs`). Customer trimming is available via `tools/customer-build.mjs` (`product-map/README.md`).
  - Enforcement is `build` only, with no licence engine (D-MR3) and no node metering, although every edition is billed `per-node`. *(Since 2026-11-11, D-PA22: a signed entitlement file narrows the Installed gate; `nodes` is recorded, not enforced, and the product map still says `enforcement: build`.)*
  - The ladder is clean on modules: Basic ⊂ Standard ⊂ Advanced ⊂ Preview through `includes:` in `offerings/*.toon`.
- **Strengths vs market:**
  - The ladder follows a recognised model: features are equal across tiers, and scale and governance differ. Neo4j Community/Enterprise and Linkurious tiers work the same way (general market). Keeping full evidential Link Analysis in Standard is a sales argument against i2's split of ANB desktop and EIA server (general market).
  - Physical absence of code (not a runtime flag) is a strong answer to "can a lower tier be unlocked by config": it cannot.
- **Gaps vs market / RFP risk:**
  - The top tier's differentiator (HA, scale-out, retention, residency) is mostly unbuilt (SCALEOUT P2s; `D-U8`; no rows for legal hold and residency). That makes Advanced vs Standard a thin step today: ABAC plus an opt-in Postgres store.
  - Per-node pricing without metering, and the Link Analysis Add-on double-listing in Basic, are both no row.
  - Preview as a GA order code: no row.
  - No release yet under the new names (last tag `v3.9.0`).
- **RFP stance on the 5 most-asked requirements (licensing and packaging):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Clear tier matrix | Comply | `docs/EDITIONS.md` matrix, generated offerings matrix |
| Upgrade without migration | Comply | same Spaces/config across builds (brochures; build-flavor model) |
| Licence enforcement / usage metering | Partial | Since 2026-11-11 (D-PA22) a signed entitlement file gates modules (`Entitlements`); no usage metering, per-node is honour-system |
| Enterprise tier with HA/DR | Partial | Advanced HA not built (`SCALEOUT-HELM-NPOD-1`) |
| On-prem / air-gapped install | Comply | self-contained bundles with embedded runtime (`release.yml` OPS-07 comment) |
- **To reach the next level:** remove Preview from the price book; decide metering (or move to flat or site pricing that needs none); deliver or descope the Advanced HA/retention promise; release 4.0.0.

---

#### Group notes

The Link Analysis showcase packs are well-built demo assets. Three are live-rehearsed with smoke runners (30/30, 29/29, 17/17), and the planted outcomes are pinned by golden tests, so they reliably support a proof-of-value conversation. None is sellable: all are `billable: false`, and none is listed in any Offering although two edition brochures sell them (`OFFERING-DECLARATION-DRIFT-1` D2). The one priced Link Analysis pack, `la-daily-feed`, is tiered into Basic, where its Alert Rules cannot dispatch (D1). Inspecto LA's real differentiator against i2, Linkurious and Bloom is evidential custody: sealed log, verifiable Dossier, four-eyes, audited reveal. Its largest gaps against Quantexa and Palantir-class products are probabilistic entity resolution, scale beyond 10^8 and multi-node HA.

Across editions the build-flavor mechanism is strong and well tested. The commercial layer is weaker:
- per-node billing with no meter (D-MR3; since 2026-11-11 a signed entitlement file gates modules, D-PA22);
- Preview carries a GA order code that the platform doc forbids shipping;
- Advanced promises HA, retention, legal hold and residency, which are unbuilt or, for retention, declined;
- nothing has been released under the new edition names.

Unverified:
- Basic behaviour of `la_index_build` (`owner: admin` with no Authenticator; no edition check in the Job path);
- whether `la-showcase-off` actually answers 404 on a created Space;
- the scope of the `intelligence` module in Advanced.

No reference-customer evidence exists in the repo, so no item can be awarded L5.


## 7. References

- Market frame: [`brochure-competitive-matrix.md`](brochure-competitive-matrix.md),
  [`brochure-competitive-landscape.md`](brochure-competitive-landscape.md) — vendor columns are published claims, not verified behaviour.
- Open work: `docs/BACKLOG.md` (row ids cited above); controls and gaps G1–G10: `compliance/controls-matrix.md`.
- Every item's hub: [`../../index.md`](../../index.md) — plan, spec, guides, generated feature-db, api-routes and tests-inventory.
- Doc accuracy baseline: [`../platform/tests-doc-claim-audit-2026-10-19.md`](../platform/tests-doc-claim-audit-2026-10-19.md).

## Changes

- 2026-11-11: licence enforcement / usage metering moved from Not offered to Partial (D-PA22).
