---
type: Capability
area: ACQ, ING, PIP
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan
description: The plan of record for the ELT module — acquisition and connectivity (ACQ), Stage-1 ingestion and parsing (ING), Pipeline authoring (PIP-1) and Pipeline execution (PIP-2 through PIP-7) — its purpose and scope, requirements of record, dated decisions, what is not built, what was refused and how it is verified. The as-built mechanism lives in the spec files of this hub, the user guide and the Step catalog.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §3.1 ACQ-1 through ACQ-7, §3.2 ING-1 through ING-6, §3.3 PIP-1 through PIP-7, and EDITIONS JOB-03/JOB-04/CP-03 (this file corrects them, see §ACQ.2, §2 and §EXE.2)
resource: platform/inspecto-acquire/src/main/java/com/gamma/acquire, platform/inspecto-engine/src/main/java/com/gamma/inspector, platform/inspecto-etl/src/main/java/com/gamma/etl, platform/inspecto-engine/src/main/java/com/gamma/parse, platform/inspecto-engine/src/main/java/com/gamma/pipeline
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# Acquisition, ingestion and Pipelines — plan (`ACQ`, `ING`, `PIP`)

**What this covers.** The plan of record for the ELT module, end to end, in four capability areas:
**acquisition** (`ACQ` — getting files from wherever they live into a Pipeline's inbox, exactly once),
**ingestion** (`ING` — how files in the inbox become typed, committed rows), **Pipeline authoring** (`PIP-1` —
how a person describes a Pipeline, how that is checked, and how it survives the editor) and **Pipeline
execution** (`PIP-2` … `PIP-7` — how the ingest engine and the Job engine run the work). For each it says what was
required, what was decided and why, what is still open, what was refused, and which tests prove it. Read it
before proposing a change, so a settled question is not reopened; the as-built mechanism is in this hub's
spec files (§3) and the screens in [the user guide](user-guide-elt.md).

**Contents**

1. [Purpose & scope](plan-elt-scope-requirements.md#1-purpose--scope) — [§ACQ.1](plan-elt-scope-requirements.md#acq1-acquisition--connectivity-acq) acquisition · [§1.1](plan-elt-scope-requirements.md#11-ingestion-ing) ingestion · [§1.2](plan-elt-scope-requirements.md#12-pipeline-authoring-pip-1) authoring · [§EXE.1](plan-elt-scope-requirements.md#exe1-pipeline-execution-pip-2--pip-7) execution
2. [Requirements of record](plan-elt-scope-requirements.md#2-requirements-of-record) — [§ACQ.2](plan-elt-scope-requirements.md#acq2-acquisition--seven-requirements) · §2.1–§2.4 · [§EXE.2](plan-elt-scope-requirements.md#exe2-pipeline-execution--the-requirement-of-record)
3. [Where the as-built mechanism lives](plan-elt-mechanism-decisions.md#3-where-the-as-built-mechanism-lives) — ingestion and authoring · [§EXE.3](plan-elt-mechanism-decisions.md#exe3-pipeline-execution--specification-where-it-now-lives) · [§EXE.7](plan-elt-mechanism-decisions.md#exe7-pipeline-execution--as-built-pointers-where-they-now-live)
4. [Decisions](plan-elt-mechanism-decisions.md#4-decisions) — [§ACQ.3](plan-elt-mechanism-decisions.md#acq3-acquisition--decisions) · §4.1–§4.3 · [§EXE.4](plan-elt-execution-decisions.md#exe4-pipeline-execution--decisions-dated-one-liners)
5. [Not built and open](plan-elt-not-built.md#5-not-built-and-open) — [§ACQ.4](plan-elt-not-built.md#acq4-acquisition--not-built) · §5.1–§5.5 · [§EXE.5](plan-elt-not-built.md#exe5-pipeline-execution--not-built)
6. [Refused & superseded](plan-elt-refused-superseded.md#6-refused--superseded) — [§ACQ.5](plan-elt-refused-superseded.md#acq5-acquisition--refused--superseded) · §6.1–§6.2 · [§EXE.6](plan-elt-refused-superseded.md#exe6-pipeline-execution--refused--superseded)
7. [Verification](plan-elt-verification.md#7-verification) — [§ACQ.6](plan-elt-verification.md#acq6-acquisition--verification) · §7.1–§7.9 · [§EXE.8](plan-elt-verification.md#exe8-pipeline-execution--verification)
8. [References](plan-elt-verification.md#8-references) — [§ACQ.7](plan-elt-verification.md#acq7-acquisition--references) · ingestion, authoring and execution
- Appendix — [Parked: parser field tiers interview kit (D13)](plan-elt-parser-field-tiers.md#parked-parser-field-tiers-interview-kit-d13)

> **Section numbers.** This plan merged four on 2026-11-18 (`DOCS-ELT-CONSOLIDATE-1`). **Plain numbers**
> (§1–§8 and their sub-sections) are the former ingestion & authoring plan's, unchanged. **`§ACQ.n`** are the
> former acquisition plan's sections and **`§EXE.n`** the former Pipeline-execution plan's, each keeping its old
> number behind the prefix and placed under the matching topic — so §ACQ.3 (acquisition decisions) sits in §4
> and §EXE.8 (execution verification) in §7. Every "§" reference inside an `ACQ` or `EXE` section was rewritten
> to the prefixed form. ⚠ A requirement id is a hyphenated code (`ACQ-4`, `PIP-2`); a section is "§" plus a dot
> (`§ACQ.4`, `§EXE.2.3`). The parser field tiers interview kit (D13), parked, is the final appendix.

> **Canonical vocabulary** (`GLOSSARY.md` §2, §5, §6-B — binding). A **Consignment** is a set of files ingested
> and processed together as one unit of work; ⛔ never *Batch* for the entity (the wire spellings `batch_id` /
> `batches` stay by decision). A **Parser** reads one format into rows; a **Grammar** is the authored options
> that tell it how, living **inline** on the parse Step; a **Grammar Template** is a Grammar saved to the
> `grammar` kind as a **copy source** — ⛔ never "linked", "shared" or "referenced". An **Archive** is a
> container file; an **Entry** is one inner file — ⛔ never *member*; **Unpack** is the Collector-level stage
> ([acquisition spec](spec-acquisition-unpack.md)). An **Expectation** is the data-quality rule
> engine; a **Segment** is one record kind a plugin ingester emits (⚠ undefined in the glossary — §5).
>
> **Acquisition vocabulary.** The two authored nouns are **Connection** (a named endpoint + credential
> definition) and **Collector** (a configured collection task bound to one Connection). ⛔ The
> acquisition entity is never a *Source* — that flipped 2026-07-14 (`GLOSSARY.md` §2/§3). A *data <!-- vocab-allow: the doc states the ban it enforces -->
> origin* is a **Stream** or a **Reference**. The pipeline TOON block is `collector:`, and the parser
> reads only that spelling — there is no `source:` block and no alias for one.
>
> ⛔ **`PIP` is one requirement range with two halves, both in this file.** `GLOSSARY.md` §14 fixes the split:
> **authoring** — the editor, the config contract, the Step catalog — is §1.2 and the plain-numbered authoring
> sections; **execution** — Run, Job, Trigger, Executable — is the `EXE` part. Of the seven rows in
> `REQUIREMENTS.md` §3.3, **only `PIP-1` is authoring**; `PIP-2` through `PIP-7` are execution's. "Orchestration"
> is folded into execution; the word is not a glossary term. Where this file disagrees with
> `docs/okf/product/layer/platform/spec-requirements.md` or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is stated in place.

## Parts

This page is split by topic into 7 parts; each part lists the sections it holds.

1. **[Purpose, scope and requirements of record](plan-elt-scope-requirements.md)** — 1. Purpose & scope · 2. Requirements of record
2. **[Where the mechanism lives, and decisions](plan-elt-mechanism-decisions.md)** — 3. Where the as-built mechanism lives · 4. Decisions
3. **[Decisions — Pipeline execution onward](plan-elt-execution-decisions.md)**
4. **[Not built and open](plan-elt-not-built.md)** — 5. Not built and open
5. **[Refused and superseded](plan-elt-refused-superseded.md)** — 6. Refused & superseded
6. **[Verification and references](plan-elt-verification.md)** — 7. Verification · 8. References
7. **[Parked: parser field tiers interview kit (D13)](plan-elt-parser-field-tiers.md)** — Parked: parser field tiers interview kit (D13)
