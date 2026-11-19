---
type: Capability
area: OPS
title: Observability & maintenance (OPS) — capability spec
description: The requirement-of-record and as-built specification for the signal ledger, metrics, the three-layer audit, provenance & conservation, run reporting, and the maintenance task library with its retention policy — one file, eight sections, machine-verified pointers.
resource: inspecto-ui/src/app/modules/admin/processing-status/
status: current
written: 2026-09-08
supersedes-rows: REQUIREMENTS §3.7 OPS-1..OPS-6 (this file corrects them, see §2)
---

# Observability & maintenance (`OPS`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.7 or `docs/EDITIONS.md`, **this file wins** and the disagreement is stated
> in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built (tracked and
> untracked), §6 what was refused or superseded, §7 the pointers into code and docs, §8 how the whole
> thing is verified. Everything named in backticks under §3 and §7 was checked against the tree on
> 2026-09-08 by a capability-pointer check (`tools/check-doc-citations.mjs`, committed 2026-09-09); the deliberate exceptions are called out where they occur.
>
> ⛔ **`OPS` is a capability; "Ops" is a Lens.** `docs/GLOSSARY.md` §14 fixes the name *Observability &
> maintenance* (it *was* "Observability & operations"). The Ops **Lens** is a UI surface owned by the
> `UI` area; its screens appear here only as *consumers* of this capability's contracts (§3.8).

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Purpose, scope and requirements of record](plan-observability-scope.md)** — 1. Purpose & scope · 2. Requirements of record
2. **[Specification](plan-observability-specification.md)** — 3. Specification
3. **[Specification — Ops Lens screens; decisions and not built](plan-observability-ops-lens-decisions.md)** — 4. Decisions (dated one-liners) · 5. Not built
4. **[Refused, as-built pointers and verification](plan-observability-refused-verification.md)** — 6. Refused & superseded · 7. As-built pointers · 8. Verification
