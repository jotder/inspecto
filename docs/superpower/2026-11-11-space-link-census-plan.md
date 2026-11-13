# Space link census — one declaration of every link between config files

**State:** IN PROGRESS — D-LC1…D-LC5 answered 2026-11-11 (§4); LC-1…LC-7 built (table below); the LC-3 shadow → refuse flip and two holds (below) remain.
Board row `SPACE-LINK-CENSUS-1`.
**Home when shipped:** `docs/okf/product/module/config/architecture-space-anatomy.md` §12.

## Progress

| Phase | State | Commits | Notes |
|---|---|---|---|
| LC-1 census + graph; impact, validate, import read it | built 2026-11-12 | `037b0a22b`, `312dd995b` | |
| LC-2 rename reads the census | built 2026-11-13 | `ac4b6c639`, `cffbdec55` | closed `PIPELINE-LINK-GAPS-1` (a), (b), (c), (e); (d) fixed by `ce1daa28d`, row closed 2026-11-13 |
| LC-3 delete refusal (every kind) and save refusal (active configs) | built 2026-11-13, **shadow** | `e1f48dabd`, `569d0c890` | `-Dlinks.delete` / `-Dlinks.save` default `shadow`; the flip to `refuse` waits for a clean shadow period (D-LC3); save refusal is Standard and Advanced only (D-LC5) |
| LC-4 boot integrity report, duplicates | built 2026-11-13 (on the SE-1a lock) | `ff074caa0`, `fc2bb8721` | the report itself is SE-1a's `GET /spaces/{id}/integrity`; added: per-Space `safety-policy.toon` `links: report \| refuse-activation` (refuse mode Standard and Advanced; `-Dlinks.*=off` still wins), a `CONFIG_LINK_BROKEN` event per new broken link, and the findings count on the Spaces screen (`GET /spaces` rows carry `integrity` for authors). **Not built:** `refuse-activation` refuses saves and deletes only; a Pipeline or Job with a dangling link is not held inactive at load (no clean seam: `PipelineScheduler` reads the cached `active`; spec-space-lock §10) |
| LC-5 column links, column impact | built 2026-11-13, report-only | `78f5e04d0`, `552a7c05d`, `a7f0e2ad4` | |
| LC-6 config ledger, hand-edit detection | built 2026-11-13 (on the SE-1a lock) | `ff074caa0` | the lock replaced the ledger; added a `CONFIG_CHANGED_OUTSIDE` event per new outside edit and `POST /spaces/{id}/integrity/accept` (`canAdminister`, every edition, audited). **Not built:** D-LC4's "an edited file stays inactive until accepted where `approval.toon` governs its kind" |
| LC-7 generated link table | built 2026-11-13 | `27b7c3848` | `tools/render-space-links.mjs --check` in the CI guards job; the hand table and the anatomy guard's check C retired |

Only targeted test classes ran on each lane; the full gate was not run. The first report's false finding,
`CENSUS-EXPECTATION-TARGET-PATH-1` (an Expectation `target` is a data path, not a Pipeline id), is fixed (`1c6f34d68`).

## 1. Problem

The files of a Space link to each other by plain text: an id, a file path, a store name or a column name
(the full list is [Space anatomy §12.2](../okf/product/module/config/architecture-space-anatomy.md)). Three
facts, all read from the code on 2026-11-11:

1. **Each check keeps its own copy of the link keys.** The same keys are hard-coded in six places:
   `PipelineRenameRoutes` (rewrite), `PipelineDependents` (impact), `ComponentIntegrity` (validate and import
   pre-check), `MetadataValidateTask`, `ComponentRoutes` and `ConnectionRoutes` (delete refusal). The copies
   drift: until 2026-11-11 the rename and impact scans read one folder level and missed every shipped Job.
2. **Most links are checked late or never.** Only two delete paths refuse; `metadata_validate` reports after the
   fact and no shipped Space schedules it; path links, `nav-menus.toon` bindings, Job `dataset` / `target` and
   every **column name** are checked by nothing.
3. **A hand edit is invisible.** The server cannot tell a file it wrote from one changed in a text editor, so
   it cannot say "this file changed outside me; here is what it now breaks".

## 2. Design

### 2.1 One census (the single source)

One Java declaration, `LinkCensus`, in `inspecto-engine` — a list of records, nothing else:

```
Link(fromKind, keyPath, toKind, type, onDelete, onRename)
  fromKind  pipeline | enrichment | job | dataset | widget | dashboard | alert-rule | … | nav-menu
  keyPath   "triggers.on_pipeline" · "job.on_pipeline" · "tiles[].widgetId" · "query.projection.datasetId"
  toKind    pipeline | dataset | widget | query | connection | schema-file | store | column
  type      ID | ID_LIST (comma-separated) | PATH | STORE | COLUMN | SQL_RELATION
  onDelete  REFUSE | WARN            onRename  REWRITE | REPORT
```

One resolver, `LinkGraph.of(configRoot)`, reads the tree once (the same file set the loader reads) and returns
edges `from-file:key → target`, each `RESOLVED` or `DANGLING`. Every consumer becomes a query over that graph:

| Consumer today | Becomes |
|---|---|
| `PipelineDependents.scan` (impact) | incoming edges of a node, transitive |
| `PipelineRenameRoutes.rewriteDependents` | incoming edges with `onRename = REWRITE`, rewritten in place |
| `ComponentIntegrity.brokenRefs` / `brokenPipelineRefs`, `MetadataValidateTask` | the `DANGLING` edges |
| bundle import pre-check | `DANGLING` edges of (registry ∪ bundle) minus those of the registry |
| `ComponentRoutes` / `ConnectionRoutes` delete 409 | incoming edges with `onDelete = REFUSE`, **for every kind** |

A key added to the census is at once renamed, impact-listed, validated, import-checked and delete-guarded. A
parity test in the style of `ImportLoaderInventoryTest` fails when a main source reads a reference-shaped key
the census does not declare.

### 2.2 Enforcement points (dependency enforced, not reported)

1. **Save** — the config write funnel (`ConfigWriteRoutes`, component saves) refuses with 422 a save that adds a
   `DANGLING` edge, naming the key and the missing target. A draft may be saved with `active: false`.
2. **Delete and rename** — every kind, not only Pipelines and Connections: 409 with the dependents, `?force=true`
   as today; rename for Datasets, Widgets and Queries, not only Pipelines.
3. **Boot** — each Space gets an integrity report at load: `GET /spaces/{id}/integrity`, one event per
   `DANGLING` edge, and the count on the Spaces screen. Per-Space mode in `safety-policy.toon`:
   `links: report | refuse-activation` (a Pipeline or Job with a dangling link stays inactive).
4. **Columns** — a Schema publishes its mapped column set; `COLUMN` links (Dataset `dateField`, Widget
   `controls`, Expectation `column`, Decision Rule `field`, Reconciliation `keyColumns` / `compareColumns`) are
   resolved against it. SQL text (`transform`, Query `text`) is resolved through the DuckDB parse tree, as
   `VIRTUAL-DATASET-SQL-RENAME-1` already does for relations. A column rename gets an impact list first, then a
   rewrite route.

### 2.3 Duplicates (one definition per thing)

The graph's node table is the duplicate check: two files claiming one `kind:id` is a finding at boot and a 409
at save. Covers what is unprotected today — a Job or Enrichment `name` in two files, a Connection both as
`*_connection.toon` and in `registry/connections/` — plus two Pipelines sharing one `dirs.poll` inbox or one
`dirs.database` store. (A duplicate Pipeline id is already refused by `CollectorService`; content-identical
registry components are already reported by `ComponentIntegrity.duplicates`.)

### 2.4 Hand-edit detection (the config ledger)

Every server-side write records `path → sha256` in `config.secrets/config-ledger.jsonl`, signed with the Space key
like the audit anchors. At boot and on hot reload, a file whose hash differs from its last ledger entry was
**changed outside the server**. The server does not refuse it — hand editing stays supported — it does three
things: emits `config.changed.outside` naming the file, re-resolves that file's edges and reports what the
edit broke, and offers `POST /spaces/{id}/integrity/accept` to record the new hash under the operator's name.
A git checkout or a template clone is accepted wholesale by one call.

### 2.5 The doc is generated

A render tool writes the §12.2 table of the anatomy page from the census, parsed from the source the way
`tools/check-space-anatomy.mjs` parses its constants. The hand-kept table and that guard's check C are then
retired.

## 3. Phases

| Phase | Delivers | Proof |
|---|---|---|
| LC-1 | `LinkCensus` + `LinkGraph`; impact, validate and import pre-check read it; parity test | existing impact / integrity tests unchanged and green; parity test falsified |
| LC-2 | rename reads it (closes `PIPELINE-LINK-GAPS-1`: `references.<n>.ref`, comma-list `on_pipeline`, bundle companions) | rename test covers each `REWRITE` link |
| LC-3 | delete refusal for every kind; save-time 422 | armed 409 / 422 tests per kind |
| LC-4 | boot integrity report, route, events, `safety-policy.toon` mode; duplicates | a Space with one planted break per link type reports each |
| LC-5 | column links and SQL resolution; column impact | planted column rename is listed |
| LC-6 | config ledger and hand-edit detection | edit a file on disk, reload: event names it and what it broke |
| LC-7 | generated §12.2; retire the hand table | render `--check` in CI |

## 4. Decisions for the operator

All five answered by the operator on 2026-11-11.

| # | Question | Decision |
|---|---|---|
| **D-LC1** | When is a save with a broken link refused? | **Only when the config is active.** A draft (`active: false`) saves with broken links; activating it is refused (422) until they resolve. |
| **D-LC2** | Boot mode default? | **`report`.** `refuse-activation` becomes the default for **new** Spaces after two releases of clean shadow — answered as D-SE2 of the [Space evolution plan](2026-11-11-space-evolution-plan.md). Existing Spaces never change on their own. |
| **D-LC3** | Delete refusal for every kind — accept the behaviour change for API clients? | **Yes.** Every kind refuses a delete while something uses it, listing the dependents; `?force=true` gives the old outcome. |
| **D-LC4** | A file changed outside the server? | **Reported, with what it broke; the edit takes effect.** In a Space whose approval policy (`approval.toon`) governs that kind, the edited file stays inactive until accepted. |
| **D-LC5** | Editions? | **The integrity report on every edition; the refuse modes and the config ledger on Standard and Advanced.** |

**Reach of D-LC3 (operator, 2026-11-11): every edition, every Space.** The wider delete refusal is not one of
D-LC5's opt-in refuse modes. Once its shadow period is clean (the delete goes through and the report lists what
it broke, with zero false findings on the shipped Spaces), every Space on every edition refuses a delete while
something uses the target, and `?force=true` gives the old outcome. This is the one accepted exception to the
evolution plan's rule 3 (an existing Space changes only by an explicit operator step): it is announced as a
behaviour change in the release that turns it on, and it keeps its own kill switch.

**What "clean" means (operator, 2026-11-13):** two releases in shadow with zero false findings on the shipped
Spaces — the same rule as the evolution plan's D-SE2 — then `-Dlinks.delete` and `-Dlinks.save` default to
`refuse`.

## 5. Not in scope

Cross-Space links (the Exchange has its own grants), links from runtime rows (Incidents, Cases) to config, and
links inside free-form saved-view content beyond `query.projection.datasetId`.
