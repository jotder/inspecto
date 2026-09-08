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

| Area | Capability | State |
|---|---|---|
| `ACQ` | [Acquisition & connectivity](acquisition/acquisition.md) | ✅ **pilot — the template's first instance** |

The remaining fourteen areas are listed with their measured load in
[`docs-consolidation-plan.md`](../../superpower/docs-consolidation-plan.md) §5.1. ⚠ Four area *names* are
non-canonical and need a `GLOSSARY.md` pass before their directories are created (§5.1.1) — `ACQ` was
chosen as the pilot partly because its name is not one of them.
