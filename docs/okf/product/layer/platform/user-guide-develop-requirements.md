---
type: Concept
title: "On Inspecto — Requirements: what an App declares"
description: "The first page of the developer track: what a partner or customer App must declare before it is built — its Industry, the data it reads, the Add-ons it needs, the editions that follow from them, its Platform range — and the conformance it must pass."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-read
---

# On Inspecto — Requirements: what an App declares

This is the first of six pages for developers who build an **App** on Inspecto: **Requirements → Architecture →
Design → Build → Test → Release**. It says what an App must declare and what it must prove before anyone writes
configuration. Read [App](../product/user-guide-concept-app.md) first if the word is new.

## An App is a declaration plus proof

An App is a folder with two files that matter before anything else:

| File | What it says |
|---|---|
| `app.toon` | what the App **is**: its id, version, the Platform versions it works with, its Industry, the Add-ons it requires, its Space Templates, its menu entries and its docs |
| `conformance.toon` | what the App **must find**: the golden data day, the Jobs to run, and the exact keys each findings Dataset must hold |

Everything else in the folder — the Space Templates, their Pipelines, Datasets, Jobs, Expectations and golden data —
serves these two. The App kit's `validate` refuses an App whose declaration does not hold, and its `test` refuses
one whose golden data does not produce what `conformance.toon` says ([Test](user-guide-develop-test.md)).

## What `app.toon` declares

```toon
id: acme-spend
title: Acme Spend Watch
version: 0.1.0
platform: >=4.0 <5
industries[0]:
requires:
  addons[0]:
templates[1]: acme-spend
nav:
  findings:
    path: data-browser
    label: Acme Spend Watch findings
    icon: heroicons_outline:magnifying-glass
    blurb: Browse what Acme Spend Watch found.
docs: docs/index.md
```

| Key | Required | The rule it is held to |
|---|---|---|
| `id` | yes | lower-case letters, digits and hyphens, starting with a letter, 2 to 41 characters; no first-party App and no other App in the same build may use it |
| `title`, `version`, `docs` | yes | `docs` names a file in the App folder |
| `platform` | yes | a version range, such as `>=4.0 <5`; it must admit the Platform version the App is built and tested against |
| `industries` | no | each id must be an Industry the Platform knows (shipped today: `telecom`, `mobile-money`); leave it empty for an Industry-neutral App |
| `requires.addons` | no | each id must be an Add-on some edition names (for example `incidents`, `caseManagement`, `reconciliation`, `scoringLists`, `linkAnalysis`) |
| `templates` | yes, at least one | each is a folder `templates/<id>/` with a `template.toon` (with a `name` and a `description`) and a `config/` folder; a template id must not clash with a first-party template or another App's |
| `nav` | no | one menu entry per key: `path`, `label`, `icon`, `blurb`, and an optional `feature`, which must be provided by a module of the App's required Add-ons |

## The decisions behind the keys

**1. Industry, or none.** An App is either for one **Industry** (*Telecom*, *Mobile money*) or Industry-neutral (Link
Analysis and Case Management work in any Industry). An Industry-specific App reads the Industry's data — its record
types and Entity Types — and a Product that carries it must name the same Industry.

**2. The data the App reads.** Write down every Dataset the App reads and writes, with its key columns. An App of an
Industry should read the Industry's shared data, so that several Apps share one ingest. *Today the Industries do not
yet publish a list of canonical Datasets to check against*, so the list lives in your own design and your templates'
Dataset definitions ([Design](user-guide-develop-design.md)).

**3. The Add-ons.** List the capabilities the App uses beyond the base: Incidents and Alert Rules need `incidents`;
Cases need `caseManagement`; Reconciliations need `reconciliation`; scores and Entity Lists need `scoringLists`.
Name them in `requires.addons`.

**4. The editions follow — you do not choose them.** `app.toon` has no edition key. The App runs on every edition
whose build carries all of its required Add-ons; the App kit's `test` works that list out and tests each one. An App
with no Add-ons runs on Basic and up; one that needs `incidents` starts at Standard. A Space Template may also state
its own floor (`minEdition` in `template.toon`). See [The editions on one page](../../../../EDITIONS.md) and
[Edition and Add-on](../product/user-guide-concept-edition-addon.md).

**5. The Platform range.** `platform` is a promise: the App works on every Platform version in the range. Start
with the current major (`>=4.0 <5`). Build only against the Platform's public, marked API, which keeps its shape
within a major ([Build](user-guide-develop-build.md)).

**6. The proof.** Decide, before building, what the App must find on a known day of data — the **planted findings**
— and what it must *not* find — the **look-alikes**. That decision becomes `conformance.toon`:

```toon
template: acme-spend
day: 2026-07-01
jobs[1]: acme_spend_detect
expectations:
  acme_spend_findings:
    key: account_id
    expected[3]: A-001,A-002,A-003
```

- `template` must be one of the App's templates and `day` a `YYYY-MM-DD` date: the day of golden data the Jobs run for.
- Each Job in `jobs` must exist in that template as `config/jobs/<job>_job.toon`.
- Each entry under `expectations` names a Dataset of that template, its key column, and the exact list of keys it
  must hold — no more, no fewer. An empty list (`expected[0]:`) says "finds nothing".
- The template must carry golden data: at least one non-empty `data/samples/<feed>/` folder.

## Requirements checklist

| Question | Where the answer goes |
|---|---|
| Which Industry, or none? | `industries` |
| Which Datasets does the App read and write, and their keys? | your design; the templates' Dataset definitions |
| Which Add-ons? | `requires.addons` (the editions follow) |
| Which Platform versions? | `platform` |
| Which screens do users get? | `nav` |
| Which roles work the App? | the template's role table ([Design](user-guide-develop-design.md)) |
| What must the golden data produce, exactly? | `conformance.toon` |

## Read next

- [Architecture: the platform on one page](user-guide-develop-architecture.md)
- [Design an App](user-guide-develop-design.md)
- [App](../product/user-guide-concept-app.md) · [Space Template](../../module/config/user-guide-concept-space-template.md)
