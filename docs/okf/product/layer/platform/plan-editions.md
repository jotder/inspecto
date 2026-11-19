---
type: Capability
area: PKG
title: Editions & packaging (PKG) — capability spec
description: The requirement-of-record and as-built specification for what Inspecto ships — the three editions and the mechanism that produces them, the nine optional modules and the twelve staged jars, the absence contract that makes a missing module answer 503, the runtime image and the Java floor, release integrity and the bill of materials, plus the deployment topologies, the security overlay, the recovery posture and the acceptance contract absorbed from the deployment-topology plan.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §3.16 PKG-1 through PKG-4, and EDITIONS OPS-07 (this file corrects them, see §2)
---

# Editions & packaging (`PKG`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.16 or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is
> stated in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built, §6 what
> was refused, §7 the pointers, §8 how it is verified.
>
> ⛔ **This area has two upstream concept homes, not one.** [`okf/backend/editions/`](../../index.md)
> owns the assembly model and the authentication story;
> [`build-test.md`](spec-build-test.md) owns bundling and reactor sizes. Neither is
> retired by this spec — both stay as the concept tier, and both are corrected below.
>
> ⚠ **This spec absorbs a plan that is now archived.** `deployment-topology-plan.md` was the only plan in
> the consolidation whose archival would lose *design* rather than provenance, and its stated distillation
> target (`okf/backend/build-run/deployment-topologies.md`) never existed. §3.9–§3.13 are that design as
> narrative; **§3.14 holds the six tables verbatim**, because acceptance criteria and contract service
> levels cannot survive being summarised by a count.
>
> 🔴 **This banner read "The plan stays in `superpower/`" until 2026-09-09**, and that sentence is why an
> earlier attempt to archive the plan was reverted. It is **superseded by operator decision** the same day:
> a plan whose decisions are signed, whose design is distilled and whose open items are filed is
> provenance, and the **board** owns the remainder. Phases 0–5 are still unbuilt — that is now the board's
> *Deployment topology live validation* row (`DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1`, which once carried it, closed
> 2026-09-27 as a DuckDB memory fix), not a reason for the document to stay current.
>
> ✅ **The loudest finding in this area was not about editions at all — and is now fixed (2026-09-09).**
> The bill of materials that ships inside every signed bundle **declared the wrong module set** — four
> first-party jars where a Standard bundle carries ten and an Advanced one eleven (§3.7). It was a
> compliance artifact wrong on the wire, and nothing in the repository could catch it. The set now lives
> once in `tools/bundle-modules.mjs`, and `tools/check-sbom-modules.mjs` fails CI when it drifts from what
> the packaging script stages.

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Purpose, scope and requirements of record](plan-editions-scope.md)** — 1. Purpose & scope · 2. Requirements of record
2. **[Specification](plan-editions-specification.md)** — 3. Specification
3. **[Specification — the plan's tables; decisions and not built](plan-editions-tables-decisions.md)** — 4. Decisions (dated one-liners) · 5. Not built
4. **[Refused, as-built pointers and verification](plan-editions-refused-verification.md)** — 6. Refused & superseded · 7. As-built pointers · 8. Verification — board rows: `DEPLOY-SERVICE-WRAPPER-1`
