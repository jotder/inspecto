---
type: Reference
title: Config and TOON - gotchas
description: TOON serialization and hand-authoring rules, the two schema shapes, component registration, flow and path resolution.
tags: [config, toon, schema, gotchas]
timestamp: 2026-11-19T00:00:00Z
item: module.config
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-config
---

# Config and TOON - gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- **TOON schema serialization** — `ConfigCodec.toToon(map)` does **not** emit tabular-array format. A schema
  whose `fields`/`rules` are Java-constructed `List<Map>` round-trips as nested maps, and the TOON parser then
  throws `Array length mismatch: declared N, found 0`. In any test that writes a schema file for TOON loading,
  write the schema as an **inline TOON string** (`fields[N]{name,selector,type}: …`), not via `toToon(schemaMap)`.
  Round-trip only works when the map was originally JToon-decoded (e.g. a `SchemaSelector` loaded from a real
  `.toon`).

- **`schema` names TWO unrelated config shapes — never resolve one to the other's spec.** The **registry
  component** is a bare column list (`{fields:[{name,type[,format]}]}`, stored under `schemas/`, authored by
  the Components pane, validated by `ConfigSpecs.schemaComponent()`); the **TOON schema config** describes a
  raw source (`raw.name` required, `raw.format`, `mapping.canonicalName`, e.g. `events/call_schema.toon`,
  validated by `ConfigSpecs.schema()`). `component_draft`/`config_schema` mean the **component** and route
  through `InspectoTools.specFor`; `/validate` means the **config** and routes through `ConfigSpecs.forType`.
  Conflating them shipped a defect that survived two slices (fixed 2026-07-27). ⚠ **Do not generalize this
  into "registry kinds have no `ConfigSpec`"** — `widget` and `dashboard` are shared words whose specs
  describe the registry components *accurately*; `schema` is the one overloaded word, and a blanket reroute
  breaks two working kinds. The collision stands deliberately (renaming breaks on-disk dirs + the UI
  `ComponentType` union) — see the exception recorded in `GLOSSARY.md`; say "schema component" / "schema
  config" when context does not disambiguate.

- **A new component kind needs TWO registrations, in different modules** — `ComponentStore.WRITABLE_TYPES`
  (else every CRUD call 400s) **and** `ComponentRegistry.TYPE_BY_DIR` (else the first write has nowhere to
  land). Adding only the first compiles and passes an unfocused build. `ComponentStoreTest`
  `everyWritableTypeHasARegistryDir` now asserts the pair; keep it.

- **Hand-authored `.toon` rules (verified live 2026-07-10, `spaces/demo` shakeout):** (1) **No `#` comments
  anywhere** — suffix-scanned loaders (`*_pipeline/_job/_connection/_alert/_queue/…`) strict-reject the file
  ("Multiple primitives at root depth"), and even the lenient registry read mangles comment lines into junk
  keys. Some loaders tolerate them today (template/escalation) — do not rely on it.
  🔴 **A hard reject is the BEST case, and not what happens for a comment between two top-level sections.** There, `JToon.decode` **silently truncates at the comment** and
  `PipelineConfig.load` accepts the result with **no error and no warning** — a 4-line explanatory block added
  to `orders/orders_pipeline.toon` dropped `output_store:`, `steps:` AND the pre-existing `collector:` gap
  detection (9 top-level keys → 6), and the file still "loaded". The job then pointed at a Stage-2 chain that
  did not exist in the parsed config. ⇒ after editing any `.toon`, **decode it and diff the top-level key
  list**; a clean load proves nothing. (2) **Lists need counts**:
  inline `members[1]: operator` or tabular `tiles[3]{widgetId,span}:`; bare `- item` lists fail (exception:
  authored-pipeline `nodes[n]:` blocks accept `- id:` maps). (3) **Alert rules need an `alert:` wrapper** and
  `severity` ∈ {CRITICAL, INFO, WARNING} — not WARN. (4) **Job-type params are FLAT keys under `job:`**
  (`JobConfig.fromMap` treats unknown keys as params); only `args:`/`bind:` nest. A `params:` wrapper in some
  design-doc sketches is doc-only, not the shipped parser.

- **Authored flows live under `config/flows/` — one dir for both readers (FIXED 2026-07-10):** the UI/HTTP
  authored-pipeline CRUD always wrote `writeRoot()/flows` (= the space's `config/flows/`), but
  `DirSpaceRoot.flowsDir()` pointed `JobService`/the T32 deletion fence at a sibling `spaces/<id>/flows/`, so a
  `type: pipeline` job couldn't resolve a UI-authored pipeline in multi-space mode. `flowsDir()` now returns
  `config().resolve("flows")`; a top-level `spaces/<id>/flows/` is dead (still tolerated by
  `SpaceLayoutContract` as historical) and new spaces no longer mint it. Regression test:
  `SpaceBootstrapTest.flowJobResolvesAFlowAuthoredUnderConfigFlows`.

- **Pipeline-internal paths resolve against the JVM CWD, NOT the space root.** A pipeline's `schema_file`,
  `grammar`, and `dirs.*` are `Paths.get(...)` in `PipelineConfigParser` with **no rebasing** to `spaces/<id>/`.
  Only the *space discovery* layer (`-Dspaces.root`, `SpaceRoot`) is space-relative. So when configs were moved
  under `spaces/<id>/config/` (`ffbf311`), every in-config path had to be rewritten to repo/bundle-root-relative
  form (`spaces/<id>/config/…`, `spaces/<id>/data/…`) — and the `SpaceMigrator` cannot auto-fix absolute or
  author-relative paths for the same reason. Shipped examples now: `spaces/default` (subscriber + events +
  connections), `spaces/ucc` (voucher; lowercase id `ucc`, display "UCC").

- **A job write body is the `job:` TOON section, and an unknown key is ABSORBED, not rejected.**
  `POST /jobs` / `PUT /jobs/{name}` hand the body straight to `JobConfig.fromMap`, so the keys are
  **snake_case** (`on_pipeline`, `on_signal`, `catch_up`) and type-specific parameters are **flat**
  alongside them — never nested under `params`. `fromMap`'s `default ->` branch sweeps every
  unrecognised top-level key into the job's parameters, so a camelCase `onPipeline` is **not a 422**:
  it becomes an inert parameter and the job silently ends up with no trigger. The UI had been sending
  exactly that since the endpoint landed, and nothing caught it — no backend test POSTed a job body at
  all, and the offline mock read `body.onPipeline` and echoed it back, so the preview looked perfect.
  Fixed 2026-08-10: `jobToWire`/`jobFromWire` (`inspecto/api/jobs.service.ts`) own the mapping, the mock
  mirrors `fromMap` **independently** (reusing the client adapter would make the round-trip
  tautological), and `ControlApiJobCrudTest` pins all three shapes. ⚠ The read side is **asymmetric on
  purpose**: `GET /jobs` is a Java record (camelCase `onPipeline`/`onSignal`) while `GET /jobs/{name}`
  is the config section (snake_case, flat) and carries **no run state** — `lastStatus`/`nextFire` exist
  only on the list, so a detail pane must merge an enable/reschedule response, never replace with it.
  An **object-valued** key is the one shape the routes do refuse (JOB-PARAMS-NESTED-IGNORED-1, 2026-11-20):
  `fromMap` would store `{"params": {"dataset": …}}` as one text value and the Job would run with its
  defaults, so `JobRoutes.refuseNestedSettings` answers 422 for any object other than `args`, `bind` and
  `incremental`, naming the key.
