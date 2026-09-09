# Capabilities — the subject tier

One document per **capability**, keyed to the area IDs that `REQUIREMENTS.md` §3 already uses and that
`BACKLOG.md` rows, `EDITIONS.md`'s gating debt and commit messages already cite. Each answers the four
questions a shift actually asks about a subject, in one place:

> *what was required · what is built · what is left · what was refused*

**Why this tier exists.** The product was sliced five incompatible ways — capability IDs, packaging
tiers, TOON config shapes, code layer, and screen layout — so no subject had an entry point. "consignment"
appeared in 48 of 192 current-tier docs and none of them answered those four questions. The file count was
the symptom; the taxonomy collision was the disease. See
[`docs-consolidation-plan.md`](../../superpower/docs-consolidation-plan.md) §2.

**What a capability doc owns, and what it must not.** It owns §2 requirements-of-record, §3 the
specification, §4 the decisions and §6 the refusals. It owns **nothing else**: §5 points at `BACKLOG.md`
rows and §7 points at the `okf/` layer concepts that hold the mechanism. ⛔ A capability doc that starts
restating mechanism becomes a fourth copy of the truth — the failure this tier exists to end.

## Areas

Names and directories are fixed by [`GLOSSARY.md` §14](../../GLOSSARY.md#14-capability-areas-the-functional-spine)
(the 2026-09-08 naming pass — four of the old `REQUIREMENTS.md` §3 headings were non-canonical and two areas
were missing). ⛔ Create a directory here only with the name that table gives it.

| ID prefix(es) | Capability | Directory | State |
|---|---|---|---|
| `ACQ` | [Acquisition & connectivity](acquisition/acquisition.md) | `acquisition/` | ✅ **pilot — the template's first instance** |
| `ING` | [Ingestion & parsing](ingestion/ingestion.md) | `ingestion/` | ✅ **area #8 — 2026-09-08** |
| `PIP` | [Pipeline authoring](pipeline-authoring/pipeline-authoring.md) | `pipeline-authoring/` | ✅ **area #16 — 2026-09-09**; owns `REQUIREMENTS.md` §3.3 **`PIP-1` only** |
| `PIP` | [Pipeline execution](pipeline-execution/pipeline-execution.md) | `pipeline-execution/` | ✅ **area #17 — 2026-09-09; the LAST slot**; owns `REQUIREMENTS.md` §3.3 **`PIP-2`–`PIP-7`** |
| `DAT` | [Data plane](data-plane/data-plane.md) | `data-plane/` | ✅ **area #7 — 2026-09-08** |
| `BI` · `INV` | [Studio](studio/studio.md) | `studio/` | ✅ **area #10 — 2026-09-08** |
| `OPS` | [Observability & maintenance](observability/observability.md) | `observability/` | ✅ **area #9 — 2026-09-08** |
| `INC` | [Alerts & Incidents](incidents/incidents.md) | `incidents/` | ✅ **area #3 — 2026-09-08** (🔴 the backend objects domain still has no concept file — §7 gap row) |
| `SPC` | [Spaces & tenancy](spaces/spaces.md) | `spaces/` | ✅ **area #5 — 2026-09-08** |
| `MET` | [Component metamodel & Catalog](metamodel/metamodel.md) | `metamodel/` | ✅ **area #6 — 2026-09-08** (🔴 the Catalog read model has no concept file — §7 gap row) |
| `API` | [Control API](control-api/control-api.md) | `control-api/` | ✅ **area #4 — 2026-09-08** (absorbs the `docs/api/README.md` "the design" archive citation) |
| `SEC` | [Security](security/security.md) | `security/` | ✅ **area #2 — 2026-09-08** |
| `AGT` · `EOI` | [Assistant](assistant/assistant.md) | `assistant/` | ✅ **area #11 — 2026-09-08** |
| `UI` | [Surfaces & Lenses](surfaces/surfaces.md) | `surfaces/` | ✅ **area #12 — 2026-09-08** |
| `PKG` | [Editions & packaging](editions/editions.md) | `editions/` | ✅ **area #15 — 2026-09-09**; absorbs the deployment plan's design — §3.9–§3.13 as narrative plus **§3.14, the six tables verbatim** (⚠ this cell said "that plan stays in `superpower/`" until 2026-09-09; the plan was **archived** that day by operator decision and Phases 0–5 moved to the board) |
| `CMP` | [Compliance](compliance/compliance.md) | `compliance/` | ✅ **area #13 — 2026-09-08**; owns the `compliance/` tree |
| `TOOL` | [Guards & repository tooling](tooling/tooling.md) | `tooling/` | ✅ **area #14 — 2026-09-09**; new area |

Measured load per area is in
[`docs-consolidation-plan.md`](../../superpower/docs-consolidation-plan.md) §5.1. Replication order is
one area per commit (plan §6 step 4); each commit must leave the link guard and the vocabulary guard green.
