---
type: Guide
title: Inspecto LA demo kit - runbook and stakeholder Q&A
description: The rehearsable stakeholder demo of the standalone Inspecto LA app (pre-flight, scenes S1-S15, short version, rehearsal records, real-feed lessons, the technical review cut) and the presenter Q&A leave-behind with evidence and confidence per answer.
tags: [module, la-core, link-analysis, demo, presales, admin-guide]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA demo kit - runbook and stakeholder Q&A

*Runbook written 2026-10-12 from the code, the seed script and the tests on `origin/master`; §0 (package flags,
Spaces, Space Templates, Demo Users) re-checked against `package.ps1`, the seed scripts and `spaces/_templates` on
2026-10-24. Q&A (§10) written 2026-10-12 against `22ccbe952`. A scene marked **UNVERIFIED (rehearse first)** has no
test or smoke probe behind one of its claims. Rehearse the whole script once on the exact bundle you will present,
then again the evening before.*

- §0-§3 pre-flight, day-of checklist, the script (S1-S15), ten-minute version · §4 claims we do not make ·
  §5 contradictions between sources · §6-§7 rehearsal records · §8 a private showcase on a real feed ·
  §9 technical / security review cut (T1-T13) · [§10 stakeholder Q&A](admin-guide-demo-stakeholder-qa.md#10-stakeholder-qa)

What this is: the step-by-step script for presenting the Inspecto LA demo on the **standalone Inspecto LA app
bundle**. It defends the architecture page ([`architecture-la-core.md`](architecture-la-core.md), cited below as "arch §n") with the running application. The claim-to-evidence map and the requirement ids (DR-*) are in
the archived [demo readiness plan](../../../../archived-documents/plans-archive/la-demo-readiness-plan.md) (all built by 2026-10-13; history only). The
technical / security review cut of this script (T1-T13) is section 9 below: rehearsed by the
assistant alone on 2026-10-24, the sceptical-reviewer rehearsal is still owed. Every control label below is the
one on screen (see the [user guide](user-guide-la-core.md)). The variants (Personal bundle, Postgres
store) are in the [showcase variants runbook](../../solution-pack/la-showcase/admin-guide-variants-runbook.md).

---

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Pre-flight, day-of checklist and the script](admin-guide-demo-preflight-script.md)** — 0. Pre-flight (do this the day before, and again 30 minutes before) · 1. Day-of checklist · 2. The script
2. **[Short version, claims, rehearsals and requirements](admin-guide-demo-short-version-records.md)** — 3. Ten-minute short version · 4. Claims we do NOT make, and what is not yet · 5. Contradictions found between sources (2026-10-12) · 6. Rehearsal record: fresh full bake, 2026-10-13 · 7. Final go/no-go rehearsal: fresh full bake, 2026-10-14 · 8. Building a private showcase on a customer's real feed (lessons, 2026-10-15/16) · 9. Technical / security review cut (T1-T13) and its rehearsal record — board rows: `LA-WHITEPAPER-DEMO-1`
3. **[Stakeholder Q&A](admin-guide-demo-stakeholder-qa.md)** — 10. Stakeholder Q&A
