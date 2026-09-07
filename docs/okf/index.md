---
okf_version: "0.1"
---

# Inspecto — Consolidated Knowledge Bundle

The **one** [Open Knowledge Format (OKF) v0.1](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle for the whole Inspecto platform: each `.md` file is one concept with YAML frontmatter; `index.md`
files are progressive-disclosure listings. Concept files **summarize and link** the deep topic docs
(each cites its authoritative doc); they don't replace them. Vocabulary is binding per
[`GLOSSARY.md`](../GLOSSARY.md).

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
  `C:/sandbox/agent-brainstorm`) that supplies Inspecto's model transport — distilled map + the
  Inspecto integration seam. The framework's authoritative docs live in its own repo.

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

* Current platform requirements + MoSCoW: [`REQUIREMENTS.md`](../REQUIREMENTS.md)
* Stakeholder-facing set: [`../stakeholders/`](../stakeholders/README.md)
* Curated map of all docs: [`INDEX.md`](../INDEX.md)

Each section keeps its own `log.md` changelog.
