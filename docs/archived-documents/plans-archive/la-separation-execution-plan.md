<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-01. Execution order for the work signed in la-separation-feasibility-plan.md (option D).
  Retire per the three-tier lifecycle in CLAUDE.md when Stages 1–2 ship: distill into the LA OKF concept,
  move leftovers to docs/BACKLOG.md, git mv to docs/archived-documents/plans-archive/.
-->

# LA separation — execution plan (Stages 1–3)

Decisions, options and spike data live in [`la-separation-feasibility-plan.md`](../../superpower/la-separation-feasibility-plan.md)
(⛔ when the two disagree, re-ground in the code). This file only orders the next work. Each stage is a
separate session; stop at each gate.

## Stage 1 — close D-0 (small)

| Id | Work | Proof |
|---|---|---|
| 1.1 | Drive the SEP-03 move in the browser pane: graph canvas in Catalog lineage, in Link Analysis, and in the Pipeline editor graph | Each renders; console clean; no G6 in the initial bundle (it stays lazy) |
| 1.2 | **SEP-02: keep `AbsentGeoLinkRoutes.SURFACE`** (recommendation) and record that as the decision in the feasibility plan §4. Revisit when the standalone host (D-5) exists | `GeoLinkAbsentSurfaceParityTest` green; decision line written |
| 1.3 | ✅ DONE 2026-10-01 — archived `link-analysis-backlog-plan.md` (G-R4 certified): distilled durable facts into `docs/okf/frontend/features/link-analysis.md`, leftovers (D-S4, D-S5, per-Collector coverage and four more) filed in `docs/BACKLOG.md` §3.12, SEP-02 recorded as a standing deferral in §6, `git mv`ed to `plans-archive/`, `docs/INDEX.md` updated | `check-doc-citations` + doc-link guards green (grep the old path across `docs/` first) |

**Gate:** D-0 declared done in the feasibility plan §4 status block.

## Stage 2 — D-S4 algorithm parity (main work)

Question: can the browser algorithms run server side with identical results?

- **Reference:** `inspecto-ui/src/app/inspecto/graph/graph-analysis.ts` (~40 exports). **Template:** the branching pattern —
  `BranchingPatternEngine.java` + `branching-parity.fixture.json`, asserted by both `branching-parity.spec.ts` and a Java test.
- **2.1 Inventory.** ✅ DONE 2026-10-01 (§7.13). Classify every export: *exact* (degree, components, shortest path, k-core…), *iterative* (PageRank,
  eigenvector, Katz, HITS, Louvain), *UI-only* (layout, styling — not ported). Write the table into the feasibility plan §7.10.1.
- **2.2 Tranche A — exact algorithms.** ✅ DONE 2026-10-01 — all 17 class-A algorithms ported (slice 1 + paths + structure lanes), mutation-checked. Port a first batch to a new package in `inspecto-geo-link` (no new module yet;
  D-1 moves it). One golden fixture per algorithm, asserted byte-for-byte in TS and Java.
- **2.3 Tranche B — iterative algorithms.** ✅ DONE 2026-10-01 (`GraphIterative`, `GraphCentrality`, `GraphSuspicion`). Tolerance-based parity: fixed iteration count, fixed seed and tie-break order,
  stated epsilon per algorithm. ⚠ Louvain is order-sensitive — pin the node order in the fixture or accept
  partition-equivalence rather than label equality; decide per algorithm and write it on the fixture.
- **2.4 Mutation check.** ✅ DONE — ~60 mutants across the lanes. For each ported algorithm, break the Java port and confirm the parity test goes red for the right values.
- **Test:** `-pl inspecto-geo-link -am -Dtest=<classes>` (commas), JDK 27; UI via `npx ng test`. Unit level only; full gate at handoff.

**Gate:** ✅ MET 2026-10-01 — D-S4 recorded as PASS in the feasibility plan §7.10.1 / §7.13. Stage 3 (plan D-1) is next.

## Stage 3 — decide D-1 (planning only, no code)

✅ DONE — design written and signed 2026-10-01 ([`la-separation-d1-design.md`](la-separation-d1-design.md), decisions 1–5); D-1 steps 1–7 are built (verified at `ab36c6e9c`).

Using Stage 2's findings, write the D-1 design (`http-spi`, `auth-spi` + OIDC move, `audit-spi` cutting the ETL edge,
the `la-inspecto` bridge) with a module dependency guard, the order of extraction, and what proves Inspecto's behaviour
is unchanged. The operator signs it before any extraction starts.

## Deliberately not now

- **D-S5** concurrency rig — needed only when D-7 (Drafts) is near.
- **D-S2** DuckPGQ — dropped 2026-10-01 (operator); DuckDB 2.0's own graph features are assessed when the pin moves.
- **D-3 / D-4 / D-5 / D-6** — SHIPPED after Stage 3 (as-built: `docs/okf/frontend/features/link-analysis.md`; designs archived in `docs/archived-documents/plans-archive/`). **D-7** (Drafts) — design active in [`la-separation-d7-design.md`](../../superpower/la-separation-d7-design.md), unbuilt.
- **Vocabulary:** the per-analyst working copy is a **Draft** (D16) — enter it in `docs/GLOSSARY.md` §13 before any code uses the word.
