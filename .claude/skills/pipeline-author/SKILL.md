---
name: pipeline-author
description: >
  Author or modify an Inspecto Pipeline (TOON config): new pipeline, new step/transform, Collector
  wiring, schema/grammar, sinks. Trigger on "create/edit a pipeline", "add a step", "wire a collector",
  "why is my pipeline config refused". Routes through the module.etl / module.engine / module.config /
  module.acquire hubs, encodes the config-safety (SaveGate) rules and the validate → dry-run loop.
---

# pipeline-author — Pipeline config, safely

Vocabulary (`docs/GLOSSARY.md`): **Pipeline**, **Collector** (acquisition entity), **Dataset**,
**Expectation**. Also apply the `java-backend` skill for any engine code change.

## Read first (hubs under `docs/okf/product/`)

| Need | Hub / slot file |
|---|---|
| Pipeline model, editor, steps | `module/etl/architecture-pipelines-steps.md`, `module/etl/spec-step-catalog.md`, `module/etl/user-guide-pipelines.md`, `module/etl/user-guide-pipeline-editor.md` |
| Parsing / schema mapping / enrichment | `module/etl/architecture-parsing-ingest.md`, `module/etl/user-guide-schema-mapping.md`, `module/etl/user-guide-enrichment.md` |
| Execution: graph, node types, lanes | `module/engine/architecture-pipeline-graph.md`, `module/engine/spec-node-types.md`, `module/engine/spec-execution-lanes.md` |
| Test run / dry run | `module/engine/spec-pipeline-test-run.md` |
| Sinks, grammar, parser options | `module/engine/spec-output-sinks.md`, `module/engine/spec-parsing-grammar.md`, `module/engine/spec-parsing-options.md` |
| TOON syntax + keys | `module/config/spec-toon-config.md`, `module/config/spec-pipeline-config-keys.md`, `module/config/admin-guide-configuration-reference.md` |
| Safety gates | `module/config/spec-config-safety.md` |
| Collectors (acquisition) | `module/acquire/architecture-collectors.md`, `module/acquire/user-guide-collector-config.md` |
| Spaces (where config lives) | `module/config/architecture-spaces.md` |

## Config-safety rules (from `spec-config-safety.md`)

- **`SaveGate.check`** (`inspecto/src/main/java/com/gamma/control/SaveGate.java`) is the one list of
  checks every config door runs: `POST /config/write`, `POST /config/patch`,
  `PUT /pipelines/{pipelineId}/graph`, `POST /pipelines/import`, `POST /validate`. Order: spec validate ·
  `ConfigSafetyValidator` · schema-file resolution · arming checks · unknown collector Connection ·
  `webhook:` block · `AcceptedConfigKeys` census · route-predicate columns · summarize measure types ·
  step configs. Any ERROR refuses the save.
- `ConfigSafetyValidator` (`platform/inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java`)
  enforces path containment — never point a dir outside the jailed roots.
- An **ACTIVE** pipeline whose `schema_file` resolves nowhere is refused (`ERR_SCHEMA_FILE_UNRESOLVED`).
- `POST /validate {configPath}` is jailed under `PathJail.allowedRoots()`.
- Unknown keys are caught by the `AcceptedConfigKeys` census — check the keys spec before adding one.
- TOON traps (quoting, `#` comment lines, arrays): `spec-toon-config.md`.

## Validate (Control API; contract in `docs/api/openapi-v1.json`)

| Step | Route |
|---|---|
| What a save would refuse (never refuses) | `POST /validate` |
| Node types / processor catalog | `GET /pipelines/node-types`, `GET /pipelines/processor-catalog` |
| Dry-run an authored pipeline | `POST /pipelines/authored/{authoredId}/dry-run` |
| Partial run up to a node | `POST /pipelines/authored/{authoredId}/run` (`?to=`) |
| Test one component | `POST /components/transform/{transformId}/test`, `/components/grammar/{grammarId}/test`, `/components/sink/{sinkId}/test`, `POST /components/mapping/validate` |
| Read / save the graph | `GET` / `PUT /pipelines/{pipelineId}/graph` (key-preserving, fail-closed) |
| Blast radius of a change | `GET /config/pipeline/{pipelineId}/impact` |
| History / restore | `GET /pipelines/{pipelineId}/history`, `GET /pipelines/{pipelineId}/history/diff` |

Live probing: use the `smoke` skill (it boots ControlApi and always stops it).

## Tests to run (unit level, `-Dtest=A,B` with commas)

- Safety: `ConfigSafetyValidatorTest` (`-pl platform/inspecto-config`).
- Dry run: `PipelineDryRunTest`, `SegmentRoutedDryRunTest`, `DryRunRejectsTest`, `FlatLaneDryRunTest`
  (`-pl platform/inspecto-engine`); `ControlApiDryRunBlindSpotsTest`, `DryRunCoverageTest` (`-pl inspecto`).
- Delegate to the `verify-runner` agent; recipes in the `build-verify` skill.

## Checklist

- [ ] Hub files read (table above); every key exists in `spec-pipeline-config-keys.md`.
- [ ] Canonical terms only (Pipeline, Collector, Dataset, Expectation).
- [ ] `POST /validate` reports no ERROR; dry-run returns the expected rows and rejects.
- [ ] New node type? It also needs a flat-config home and a `spec-node-types.md` entry.
- [ ] New route? Use the `endpoint` skill (openapi-v1.json entry + every gate tested).
- [ ] Relevant unit tests green; the owning hub slot file updated.
