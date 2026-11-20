---
type: Plan
title: module.config - board row detail
description: The narrative behind module.config's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, config, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.config
audience: internal
status: plan
verified: 2026-11-20 code-reviewed
code: platform/inspecto-config
---

# module.config - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### SPACE-LINK-CENSUS-1

- **`SPACE-LINK-CENSUS-1` the link census: the default flip and the plan scope not built** — the census (LC-1…LC-7) shipped 2026-11-12/13; its delete and save refusals are built and ready in `refuse` (opt-in) and stay in shadow by default (as built, decisions D-LC1…D-LC5 and the 2026-11-16 corpus evidence: `okf/product/module/config/architecture-space-anatomy.md` §12.6; plan archived). **Waiting on the operator:** the flip of `-Dlinks.delete` / `-Dlinks.save` to `refuse` by default — D-LC3 asks for two releases of clean shadow from 2026-11-13, none has shipped, and S9 did not override it (five test classes that save links to missing targets then need the `shadow` opt-out). Left from the plan: (a) a rename route for Datasets, Widgets and Queries (only a Pipeline renames, rewriting its links); (b) a duplicate `kind:id` refused at save (the Space lock reports it at boot only), and two Pipelines sharing one `dirs.poll` inbox or `dirs.database` store, not traced; (c) a column rename rewrite route (column links stay report-only; the opt-in Schema-save stop is SE-3b of `SPACE-EVOLUTION-1`); (d) in the single-tenant layout the delete check reads only the write root, so a dependent held in the config directory is not listed (a missed refusal, never a false one). **2026-11-16 (operator, MoSCoW S9):** build now; the default flip stays the operator's D-LC3 call. Only targeted test classes and the affected modules ran; the full gate was not run. → `okf/product/module/config/architecture-space-anatomy.md`

### SPACE-EVOLUTION-1

- **`SPACE-EVOLUTION-1` the release-gated rest of the Space evolution** — every startable step is built (2026-11-12/13: SE-0, SE-1a/1b, SE-2a/2b, SE-3a/3b, SE-4 step 1, SE-5a/5b, SE-6a/6b, the finding baseline; as-built table and decisions D-SE1…D-SE13 on the hub page). Left, gated by the operator's own decisions: (1) enforcement becomes the default for **new** Spaces (SE-1b holds, SE-2b rename rewrite, SE-3b Schema-save stop, and with them `SPACE-LINK-CENSUS-1`'s LC-3 refuse flip, whose `refuse` mode is built and ready since 2026-11-16) after two releases of zero false findings counted from the 2026-11-13 baseline (`SpaceFindingBaselineTest`; D-SE2, D-LC3); (2) SE-4 step 2, the migrator into `spaces/<id>/state/` (`--dry-run`, copy → verify → atomic rename), one release after the step-1 readers, then step 3, the default for new Spaces; the old paths retire at the major release D-SE8 names when SE-4 ships; (3) the operator's call whether hold 2 also blocks a manual run of a held object. → `okf/product/module/config/plan-space-evolution.md`

### SPACE-TO-APP-CONSOLE-1

- `SPACE-TO-APP-CONSOLE-1` — **turn a working Space into a Space Template, and into an App, from the console.** Today the public API has Metadata Bundle export / import (`/bundle/*`) and promotion (`/promotion/preview`, `/promotion/apply`), and `/apps` installs, upgrades or removes a signed App package, but nothing turns a Space a builder made into a template or a package: only the App kit CLI (`tools/inspecto-app.mjs`) does. Found while planning the Handbook (gap rule, plan §4.5): tutorial T5 "Ship it" has to send a console builder to a command line. Wanted: *Save as Space Template* on a Space (components, sample data, a manifest) and *Package as App* (name, version, Platform range, required Add-ons, signing where an entitlement key exists), each previewed before writing. → [`superpower/2026-11-19-user-enablement-and-agent-kit-plan.md`](../../../../superpower/2026-11-19-user-enablement-and-agent-kit-plan.md) §4.5

### SPACE-ROOTED-DATA-ROOT-MIGRATION-1

- `SPACE-ROOTED-DATA-ROOT-MIGRATION-1` — **an install whose `data_root` is rooted (or absent, i.e. `-Dspaces.data.root`) is still not relocatable, and there is no route that migrates it.** `SPACE-RELOCATABLE-ROOTS-1` (2026-11-14) made a RELATIVE `data_root` stamp relatively, so such an install moves freely; an older one keeps absolute `.inspecto-space` stamps and boots 0 Spaces at a new path. Q1 of the plan, left open on purpose: the manual fix is two steps and is documented (stop the server, rewrite `space.toon` to the relative form, delete the stamps — they are rewritten on the next boot), and nobody has asked for more. If demand appears: a `POST /spaces/<id>/relocate` route, or a boot-time adoption that rewrites an absolute stamp whose Space has moved — ⛔ the second weakens the copied-Space guard and needs a design, not a patch. → [`okf/product/module/config/architecture-space-anatomy.md`](architecture-space-anatomy.md) §2.1

## Standing refusals and decisions

Board §6: not work — kept so nobody re-files it.

### Space comparison beyond the on-demand admin run

- **Space comparison beyond the on-demand admin run** — ⛔ decided 2026-10-18 under operator delegation (closes P3 *Job framework — space-to-space comparison (residuals)*): (a) **no scheduled / authored cross-Space comparison** — a run with no Subject has nothing to authorize a cross-Space read, and a persisted, attributable, revocable grant is a new security model built for one storage report nobody has asked to schedule; (b) **no "compare every registered Space"** — `SpaceConfigRoot` deliberately enumerates none, and the dialog already lets an admin pick every hosted Space; (c) **no persisted comparison rows** — the result is a cross-Space aggregate whose residency call (design Q3) stays untaken; the `space.comparison.completed` Signal is the record. Trigger: an operator asks by name for a scheduled comparison or a stored comparison history. → `okf/product/module/processor/spec-jobs.md` § Space comparison · `archived-documents/plans-archive/space-comparison-design.md` §5
