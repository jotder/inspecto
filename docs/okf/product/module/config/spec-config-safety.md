---
type: Concept
title: Config Safety Validator
description: The hard-fail gate that path-jails writes, bounds numeric config, and allow-lists output formats.
resource: platform/inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java
tags: [config, safety, validation, path-jail, security]
timestamp: 2026-06-28T00:00:00Z
---

# Config Safety Validator

`ConfigSafetyValidator` (`platform/inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java`) is a
purely-static, zero-dependency hard-fail gate (since v3.5.0). `check(configType, rawMap, policy)` returns
`ERROR`-severity `Finding`s for any violation. It enforces three things:

* **Path jail** — every `dirs.*` field + `output.ducklake.data_path` must resolve under the policy's
  `allowedRoots`; rejects `..` escapes, UNC paths, and symlink escapes (real-path re-checked).
* **Numeric bounds** — `processing.threads`, `processing.duckdb_threads`, `collector.consignment.max_files` (and the legacy `processing.batch.max_files`), and
  the `skip_*` values against policy limits; `retention_days >= 1` when duplicate-check is on.
* **Output allow-list** — `output.format`/`output.compression` restricted to known values; DuckLake requires
  its connection fields when enabled.
* **Enrichment `references.<name>` entries** (2026-08-13) — each entry must be a map carrying **exactly one
  of `path` or `ref`**; the entry name and a by-name `ref` must be SQL identifiers; `as_of` must be an ISO
  date/date-time and requires a by-name `ref` (a plain `path` file carries no version history). These mirror
  the hard-fails `EnrichmentConfig.fromMap` applies at LOAD, so a hand-authored or API-written config is
  refused at the 422 write gate rather than at registration.

* **Config-declared refs** (2026-08-14) — `processing.schema_file`, `processing.mapping_file`,
  `parsing.grammar`, `processing.grammar`, and **every row** of the multi-schema table form
  (`schemas[N]{…,schema_file,…}`). These mirror what `PipelineConfigParser` now enforces at LOAD
  (see [path containment](spec-config-safety-path-containment.md#path-containment-one-primitive-five-callers) below), so a ref is refused at
  authoring for the same reason and against the same roots.
  * ⚠ **`grammar` has two spellings and `parsing.grammar` is the design-of-record** — it *wins over*
    the legacy `processing.grammar`, so gating only the legacy key leaves the preferred one open.
  * ⛔ **A registry reference is an id, not a path.** `schema/<id>`, `grammar/<id>` and `mapping/<id>`
    share these keys with plain paths. Jailing one resolves it against the working directory and
    reports a false escape **whenever `allowedRoots` is not the CWD** — i.e. on precisely the
    deployments that declare `-Dassist.safety.roots`. `checkConfigRef` skips them; the parser rewrites
    a reference to `registry/<kind>/<id>` and jails *that*, which is the value actually read. The
    prefix list is duplicated from `PipelineConfigParser` deliberately — that class is in
    `inspecto-etl`, **above** this module, so importing it would invert the dependency.

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Path containment and the config write routes](spec-config-safety-path-containment.md)** — Path containment: one primitive, five callers · Object-store containment — `PathJail.requireObjectStore` (2026-10-18, `SCALEOUT-OBJSTORE-DATABASE-DIR-1`) · The `/config/*` write routes never reach `registry/` or a reserved file (`CONFIG-WRITE-REGISTRY-1`, 2026-09-27) · Decision 2026-09-23 — a relative DATA path resolves under the Space directory
2. **[Allowed roots, data refs and the one save gate](spec-config-safety-roots-save-gate.md)** — A Space's allowed roots: declared + its OWN base (tier 3 2026-08-14, narrowed 2026-09-24) · Data refs: a second verdict, deliberately not `PathJail` · One save gate for every door — `SaveGate` (2026-09-23)
3. **[Job config decisions, the accepted-key census and the classification vocabulary](spec-config-safety-keys-vocabulary.md)** — Decision 2026-09-06 — job configs get a save-time spec; the depth rule stays · The accepted-key census — which config types have one, and why not the rest · The classification vocabulary — a save refuses a class the Space does not know (`CLASSIFICATION-UNMASKED-CLASS-1`, 2026-11-14) · Open rows this concept owns
4. **[Maker-checker and Safety Policy tier files](spec-config-safety-maker-checker.md)** — Maker-checker — Pending Changes (2026-09-26) · Safety Policy tier files — loading and the unreadable refusal (2026-10-03/04, `DUCKLE-C6-POLICY-NARROWING-1` S2a + S2b)
