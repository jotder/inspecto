---
okf_version: "0.1"
---

# Inspecto — Consolidated Knowledge Bundle

The **one** [Open Knowledge Format (OKF) v0.1](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle for the whole Inspecto platform: each `.md` file is one concept with YAML frontmatter; `index.md`
files are progressive-disclosure listings. Concept files **summarize and link** the deep topic docs
(each cites its authoritative doc); they don't replace them. Vocabulary is binding per
[`GLOSSARY.md`](../GLOSSARY.md).

⚠ **One deliberate exception, and it is the whole point of the newest tier.** A
[`capabilities/`](capabilities/index.md) document **does replace** what it supersedes: it is the
*requirement of record* for its area, and `REQUIREMENTS.md` §3 was stripped to an index on 2026-09-09
precisely so that each requirement has exactly one home. Everything else in this bundle still
summarizes-and-links.

Consolidated 2026-07-07 from the two former bundles (`docs/okf-backend/`, `inspecto-ui/docs/okf/`) plus
a new distilled section for the agentic framework.

## Sections

* [Frontend](frontend/) — **inspecto-ui**, the Angular operator console: architecture, conventions,
  the shared design system, the ~35 feature screens (incl. the Studio, Geo Map Analysis, and Link
  Analysis studios), API services.
* [Backend](backend/) — the Java engine + control plane: modules, engine layers, acquisition,
  the versioned `/api/v1` contract, pipeline-graph, components, config, editions & security, agent,
  build/run, gotchas.
* [Agentic](agentic/) — **eoiagent**, the embeddable agent framework (separate repo,
  upstream repo `jotder/inspect-agent`) that supplies Inspecto's model transport — distilled map + the
  Inspecto integration seam. The framework's authoritative docs live in its own repo.

## Capabilities — the subject tier (new 2026-09-08)

* [Capabilities](capabilities/index.md) — **one document per capability**, keyed to the area IDs fixed by
  [`GLOSSARY.md` §14](../GLOSSARY.md#14-capability-areas-the-functional-spine) (⚠ not by
  `REQUIREMENTS.md` §3, which is now an index of these documents rather than their source). Answers *what was required · what is built · what is left · what was refused* in one
  place, because the sections above answer only the second of those and answer it by CODE LAYER. A
  capability doc owns its requirements, specification, decisions and refusals; mechanism stays in the
  sections above and open work stays in [`BACKLOG.md`](../BACKLOG.md). **All seventeen areas shipped
  between 2026-09-08 and 2026-09-09**; `ACQ` was the pilot.

## Cross-cutting

* [`living-operational-system.md`](living-operational-system.md) — the platform-wide **north star**: seven
  cooperating networks over one metadata model, why AI is just another decision engine, and the
  principle→enforcement table. Shape, not state — each network points at its own as-built concept.

## How to read this tier

**The `okf/` tier is a constraint register, not a backlog.** Its invariants and traps are the rules
future work must not break — every `writeAndTrace` caller declares a write scope; the UI offers the Step
switch on the `route:<key>` relation and never the lift's `sink__d<i>` spelling; `supersedeOtherRevisions`
is full-recompute-only and `keep` is required; a stale `branch_commit_<batchId>.log` in a shared `%TEMP%`
makes a batch write **nothing**. Several are recorded precisely because the repo has already paid for
violating them. A trap here is not an open item — open work lives in [`BACKLOG.md`](../BACKLOG.md).
*(Distilled 2026-09-07 from `gate-register.md` §6 when that register was archived.)*

## Companions

* Cross-area rollup — scope, conventions, NFRs, MoSCoW, sequencing, risks, traceability, and the index
  of which capability doc owns which requirement: [`REQUIREMENTS.md`](../REQUIREMENTS.md)
* Stakeholder-facing set: [`../stakeholders/`](../stakeholders/README.md)
* Curated map of all docs: [`INDEX.md`](../INDEX.md)

Each section keeps its own `log.md` changelog.
