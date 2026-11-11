# Space link census — one declaration of every link between config files

**State:** PROPOSED 2026-11-11 — nothing built, decisions D-LC1…D-LC5 open. Board row `SPACE-LINK-CENSUS-1`.
**Home when shipped:** `docs/okf/product/module/config/architecture-space-anatomy.md` §12.

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

- **D-LC1** Save-time refusal: hard 422, or 422 only when the config is `active: true`? *(proposed: active only)*
- **D-LC2** Boot mode default: `report` or `refuse-activation`? *(proposed: `report`, Advanced may set refuse)*
- **D-LC3** Delete refusal for every kind is a behaviour change for existing API clients — accept? *(proposed: yes,
  `?force=true` keeps the old outcome)*
- **D-LC4** Hand-edit detection: report only, or hold the edited file inactive until accepted under maker-checker
  on Spaces with an approval policy? *(proposed: report; hold only where `approval.toon` governs the kind)*
- **D-LC5** Edition: integrity report on every edition; refuse modes and the ledger on Standard and Advanced?

## 5. Not in scope

Cross-Space links (the Exchange has its own grants), links from runtime rows (Incidents, Cases) to config, and
links inside free-form saved-view content beyond `query.projection.datasetId`.
