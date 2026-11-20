---
type: Concept
title: Config Safety Validator — Job config decisions, the accepted-key census and the classification vocabulary
description: Part 3 of 4 of Config Safety Validator: Decision 2026-09-06 — job configs get a save-time spec; the depth rule stays; The accepted-key census — which config types have one, and why not the rest; The classification vocabulary — a save refuses a class the Space does not know (CLASSIFICATION-UNMASKED-CLASS-1, 2026-11-14); Open rows this concept owns
tags: [config, safety, validation, path-jail, security]
timestamp: 2026-06-28T00:00:00Z
item: module.config
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-config
---

# Config Safety Validator — Job config decisions, the accepted-key census and the classification vocabulary

Part 3 of 4 of [Config Safety Validator](spec-config-safety.md) · previous: [Allowed roots, data refs and the one save gate](spec-config-safety-roots-save-gate.md) · next: [Maker-checker and Safety Policy tier files](spec-config-safety-maker-checker.md).

## Decision 2026-09-06 — job configs get a save-time spec; the depth rule stays

(a) Job `.toon` files bypass `ConfigSafetyValidator` at save because no `ConfigSpecs.job()` exists, so
containment is enforced only at run (`PipelineJobRunner`). **Decided:** close it — add a job spec and route job
writes through the same 422 gate, keeping the run-time check as belt. **Shipped 2026-09-06:** grounding found
`ConfigSpecs.job()` already existed but `POST/PUT /jobs` skipped both it and the validator; `JobRoutes.parseJob`
now runs both (ERRORs 422 with `field: message`), `ConfigSafetyValidator` gained a `job` case jailing
`data_dir` / `pipeline_config` / `dir` / `backup_dir` / `archive` / `target_dir`, and the spec's stale
"type must be enrich|report|maintenance" rule — which would have refused every pipeline job — was removed.
(b) `requireTopLevelSinks` is a rule about literal directory nesting ("no store inside another store's tree"),
not a jail; resolving real paths would change its answer for the wrong reason. **Decided:** keep as designed —
a standing refusal (BACKLOG §6).

## The accepted-key census — which config types have one, and why not the rest

`AcceptedConfigKeys` (`platform/inspecto-config/src/main/java/com/gamma/config/spec/AcceptedConfigKeys.java`) is the
second gate folded into the same 422 at every save path — since G3 (2026-09-23) through the one shared
gate `SaveGate.check`, see [one save gate](spec-config-safety-roots-save-gate.md#one-save-gate-for-every-door--savegate-2026-09-23) below:
**a key no component reads is refused** with
`ERR_UNKNOWN_CONFIG_KEY` and a near-name suggestion (`DUCKLE-C3-DEAD-PROPERTY-1`). A dead key is a
*silent loss* — the save answers `written: true` and the engine never looks at it.

**Four of the nine config types have a census: `pipeline`, `alert`, `meta` and `enrichment`.** The
other five (`job`, `schema`, `expectation`, `widget`, `dashboard`) have no census **in this class**:
`unknownKeyFindings` returns nothing for a type with no census. Three of them are gated elsewhere:
`widget`, `dashboard` and `expectation` are refused at the top level by
`ComponentRoutes.refuseUnknownComponentKeys` (checked in code 2026-11-14). So `job` and `schema` are the
two types that are **fail-open by omission, and that is stated rather than accidental**.

🔴 **Why the missing five cannot simply be switched on.** Two authorities read every config: what
`ConfigSpecs` *declares*, and what the engine's hand-written parser *navigates*. An accepted set derived
from `ConfigSpecs` **alone is unsound** — it would refuse keys the engine honours today. This is not
hypothetical; each of these has confirmed undeclared-but-engine-read keys:

| Type | Undeclared keys the engine reads | Blocker |
|---|---|---|
| `job` | `on_signal`, `when`, `catch_up`, `coalesce`, `args`, `bind` | `JobConfig.fromMap` also funnels **any** other key into an open `params` bag — a census needs a job-type registry, not a parser walk |
| `expectation` | `when` (required for `kind: condition`; ⚠ the spec's `kind` enum omitted `condition` entirely until 2026-09-17) | ⛔ **a census here is close to a no-op too**: expectations are authored through `/expectations*` (`ExpectationRoutes`), not `/config/write`, and the persisted content carries `lastResult` / `createdAt` / `updatedAt` bookkeeping (`ExpectationRoutes.java`) that no `ConfigSpec` declares — the same shape that struck `widget`/`dashboard` |
| `schema` | `mapping.fields`, `mapping.rules[].targetColumn`, `partitions[]` | ⛔ **there is no single schema parser to census.** Its blocks are navigated by a dozen classes across `inspecto-etl` — `Identifiers.validateSchema`, `DataTransformer`, `PartitionDef`, `ParserSpec`, `SourceZones`, `TypeFlow`, `BoundaryScanner`, `SchemaMappingDrift`, `DuckDbCsvIngester`, `ConsignmentPlanner`, `PipelineConfigParser` — so "the reads" are not enumerable from one source file and the ratchet idiom cannot be written |
| `widget`, `dashboard` | none found in a Java reader | ⛔ **a census here would be a no-op**: neither type is ever written through `/config/write`. The UI saves both through the component-store routes (`POST`/`PUT /components/{kind}`), which never call this class. A gate belongs in `ComponentRoutes` — and it is not a table away, because the persisted body also carries `name`, `owner` and `shares`, which no `ConfigSpec` declares |

⇒ **A type earns a census only when its parser's reads can be PROVEN from source**, and the proof is a
ratchet test that fails when the two authorities drift.

**`alert` earned one (2026-09-16).** `AlertRule.fromMap` (`platform/inspecto-engine/.../alert/AlertRule.java`)
is the easiest case in the codebase: one flat block of literal `alert.get("…")` calls, no dynamic key
access, no nested sub-parsers. Seven of its ten keys are declared by `ConfigSpecs.alert()`; the other
three are `AcceptedConfigKeys.ALERT_PARSER_ONLY` — `alert.dataset`, `alert.measure` (the BI-5
measure-rule shape) and `alert.when` (the ledger-row scope filter). All three are **live**, which is
exactly why a spec-only census would have broken every measure rule authored today.

⚠ **Granularity differs per type, and must.** For `pipeline` the census stops at the top level and
descends only into `processing.*`. An alert file is **one** block — `alert:` — so a top-level-only
census would accept every alert config whole and catch nothing; `alert` is therefore a *censused parent*
and the checker descends one level into it. `censusedParents(type)` is per-type for this reason.

**`meta` earned one (2026-09-16), and it is the case that shows the test is about GRANULARITY, not
about the word `entrySet`.** The one reader of a `*_meta.toon` is `SemanticModel.load`
(`platform/inspecto-engine/.../catalog/SemanticModel.java`; its only caller is `ServiceBootstrap`),
and at the level the census works — the **top level** — it is five literal `raw.get("…")` reads:
`name`, `tables`, `kpis`, `reports`, `domain`. All five are declared by `ConfigSpecs.meta()`, so
**`meta` has no parser-only list at all** — five reads, five accounted for.

🔴 `SemanticModel.load` **does** call `entrySet()`, three times — but one level DOWN, over `tables`,
`kpis` and `reports`, whose keys are names the **author invents** (a table ref, a KPI name, a report
name). That is an unbounded namespace, so `meta` is deliberately **not** a censused parent: descending
would refuse every KPI anyone ever names. A scan that merely asked *"does this file contain
`entrySet`?"* would have refused the type for the wrong reason. ⚠ Both committed `*_meta.toon` files
carry exactly the six declared keys, so nothing on disk regresses — pinned, not assumed.

**`enrichment` earned one (2026-09-16), and it is the first type where the DESCENT carries the value.**
🔴 The premise this table carried until that day was **wrong**: it listed `input`, `output`,
`triggers.*` and `transform`/`transform_file` as *undeclared keys the engine reads*, and
`ConfigSpecs.enrichment()` declares **every one of them**. Grounded against the code, exactly **one**
top-level block is undeclared — `references` — and that is the whole of
`AcceptedConfigKeys.ENRICHMENT_PARSER_ONLY`.

`EnrichmentConfig` (`platform/inspecto-engine/.../enrich/EnrichmentConfig.java`) reaches the root map
through seven literal reads — `name`, `transform`, `transform_file`, `references`, `triggers`, and
`ToonHelper.requireSection(raw, "input"|"output")` — with no `keySet`/`entrySet`/`forEach` over the
root. Seven reads, seven accounted for. Every **other** component that reads a `*_enrich.toon` map
reads a subset of the same declared blocks: `PipelineGraphRoutes`,
`PipelineBundleRoutes`, `PipelineRenameRoutes`.

⚠ **`input`, `output` and `triggers` are censused parents**, and unlike `alert` that is about value
rather than necessity: an enrichment's real settings live one level down, so a top-level-only census
would accept `input: {databse: …}` whole. The descent is sound because `fromMap` reads those three
blocks through plain locals (`in`, `out`, `tr`) with a literal key each — via **two** spellings,
`local.get("…")` *and* the `req(local, "…", …)` helper — and every leaf it reads is spec-declared.
🔴 The `req(…)` half is why the falsify-the-scan test is not ceremony: the first version of the ratchet
scanned only `local.get(…)`, missed `input.database` / `output.database`, and **the falsify test is
what caught it**.

⛔ `references` is accepted **whole** and is deliberately not a censused parent: `fromMap` iterates its
`entrySet()` over view names the author invents. Same shape as `meta`'s `tables`/`kpis`/`reports`.

⚠ **BREAKING, and one committed file moved.** `spaces/demo/config/orders/orders_daily_enrich.toon`
carried `version: 1`, which no component reads — the same dead key `pipeline` started refusing on
2026-09-16 — so it is removed rather than declared, and
`EnrichmentKeyCoverageContractTest.everyCommittedEnrichmentConfigSurvivesTheCensus` pins that nothing
on disk regresses. 🔴 **The committed `*_pipeline.toon` samples were NOT given the same treatment when
`pipeline` was censused**: **24** of them still carry `version:`, so re-saving any sample pipeline
through `/config/write` 422'd. ✅ Fixed 2026-09-16 (`45eff375e`) — `PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1` below.

⚠ The **flat** `alert-rule` component shape (`AlertRoutes`, `ComponentStore`) is a *different config
type string* and is written through `/alerts/rules*`, not `/config/write` — it never reaches this census.

Pinned by `AcceptedConfigKeysTest` (the checker), `AlertKeyCoverageContractTest`,
`MetaKeyCoverageContractTest`, `EnrichmentKeyCoverageContractTest` and
`PipelineKeyCoverageContractTest` (the four source-derived ratchets), and
`AcceptedConfigKeysDocContractTest` (the generated pipeline table). Each ratchet includes a
*falsify-the-scan* test, because a scan that silently matches nothing passes every other assertion.

## The classification vocabulary — a save refuses a class the Space does not know (`CLASSIFICATION-UNMASKED-CLASS-1`, 2026-11-14)

A column classification was free text. Masking reads it, so a class no reader knows masks nothing, and nobody was
told: three shipped templates classed personal identifiers `CONFIDENTIAL` and served them raw. Operator decision
2026-11-14: **a closed vocabulary, refused at save.**

**What is known, per Space** (`ClassificationVocabulary`, `inspecto/src/main/java/com/gamma/service/`):

| Class | Why it is known |
|---|---|
| `MSISDN`, `IMSI`, `ACCOUNT`, `PII` | `ColumnClassification.SENSITIVE`: Risk Score evidence, publication, embed links and BI in `maskingMode: all` mask them |
| a class an Entity Type of the Space claims, or the id of one | Onboarding and the Link Analysis coverage route read the column as that Entity Type (`OnboardMatches.typeOf`, which also matches a type id; onboarding proposes the id for a type that claims no class). BI results in the default `typed` mode mask a **claimed** class when the type is `masked`. ⚠ The masking readers do not match a type id (`BiResultMasking.typeOf`, `EntityMasking.typeOf`), so an id that is not also a claimed class saves and masks nothing (`MASKING-TWO-VOCABULARIES-1`) |
| `INTERNAL` | `ColumnClassification.NOT_MASKED`: the stated "needs no masking". No reader acts on it |
| empty | nothing stated |

Anything else is an ERROR with the code `ERR_UNKNOWN_CLASSIFICATION`; the message names the column, the classes
that mask and `INTERNAL`. Matching is case-insensitive and trims, as every reader does.

**Where it runs.** Two doors, because a classification is saved through two:

- `SaveGate.check` for the config types `schema` and `pipeline` (a Step's `fields[]` row carries the same
  metadata): `POST /config/write`, `POST /config/patch`, the validate routes, and the Pipeline document of a
  pipeline bundle import. `TemplateSeedGate` runs the class check alone on a template's `*_schema.toon` files
  (the rest of the schema gate was never run at a seed).
- `ComponentRoutes.validateKind` for the component kinds `dataset` and `schema`: the authoring route, both
  bulk writers (a bundle import, a BI template apply) and the onboarding column route
  (`OnboardImport.setColumn` ends in the same call, so it has no check of its own).

The walk is generic: any map of the document that states a `classification` is judged, so a new place that
carries the label is covered without a new rule.

**Served to the editors** (`UI-CLASSIFICATION-PICKER-1`, 2026-11-14). `GET /classifications` returns the Space's
vocabulary from the same method the gate judges with (`ClassificationVocabulary.vocabulary`): `{class, masked,
source: sensitive | entity-type | not-masked, entityType?, label?}`, sorted. It is an open read like
`GET /inv/entity-types` (reads are gated at the Space/ABAC layer, not by capability). The schema metadata grid and
the `sql` Step's output grid offer it as a picker (`<inspecto-classification-select>`) that marks the classes that
mask, keeps a stored class the Space does not know as a flagged "(unknown)" option so an old config can be fixed,
and falls back to a text input if the list cannot be read (the save still judges). `masked` is the one masking rule
below, so a class marked "masks" masks at every reader.

🔴 **Save-time only, and the two doors differ on a class already stored.** A file already on disk still loads,
and its unknown class still masks nothing; the gate stops new ones.

- At the component door a class the stored component already carries is **kept**: only a class the save
  introduces is refused. Half the writers of a Dataset have no author present (the inbox watch, compaction, a
  pending Alert Rule, a role or unit change), and a Space's vocabulary shrinks when an Entity Type is retired;
  neither may make every later edit of that Dataset fail. The tolerance is per component and per class.
  ⛔ `TemplateSeedGate` judges a template's components a second time with nothing stored: the staged tree IS
  the document, so the tolerance alone would keep every class of the template itself.
- At `SaveGate` the whole draft is judged: the author is present, so a schema or Pipeline with a stale class
  is refused until the class is fixed. ⚠ That includes a Pipeline history restore.

**The bulk file importers** (`CLASSIFICATION-BULK-IMPORT-UNGATED-1`, closed 2026-11-14). They write config
files as bytes, so they are judged on disk after the write, inside their import journal:
`ClassificationVocabulary.findingsWritten(root, written)` reads every written `.toon` outside `registry/` (a schema
file is whatever name its Pipeline gives it, so no suffix filter), a written `<base>_structure.csv` together with its
schema, and the `registry/datasets/` and `registry/schemas/` entries. Entity Types are read from the target AFTER the
write, so a Space bundle that brings its own `link-analysis.toon` is judged against it. Nothing stored is kept: an
import is not an edit. A refusal rolls the whole import back and answers:

| Door | Refusal |
|---|---|
| Pipeline bundle `POST /pipelines/import` (schema satellites) | 422, `written: false`, an `ERR_UNKNOWN_CLASSIFICATION` finding |
| Collector zip bundle `POST /import` | 422 `CONFIG_VALIDATION_FAILED` |
| Template upgrade `POST /spaces/{id}/origin/upgrade` | 422 `CONFIG_VALIDATION_FAILED`; the origin record is unchanged |
| Space from a bundle `POST /spaces/import` | 422 `CONFIG_VALIDATION_FAILED`; no Space and no directory, so a retry under the same id works |
| Space promotion apply | 422 with `findings`, the route's own refusal shape; removals are put back too |

`TemplateSeedGate` reads a template schema merged with its `_structure.csv`. An App install writes no Space config: an
App template with an unknown class still installs, and is refused when a Space is created from it.
⚠ Only the unknown-class refusal of `createFromBundle` removes the half-made Space directory; its other refusals
(a refused entry name, an I/O failure, a failure after the write) still leave it, and a retry answers 409.

**One masking rule** (`MASKING-TWO-VOCABULARIES-1`, 2026-11-14; decided by the assistant under the operator's
delegation and operator-confirmed the same day: fail closed). A column is masked for a viewer who cannot reveal if its class is one of the four sensitive
classes, is claimed by a masked Entity Type of the Space, or is the id of one — `EntityTypes.maskedClasses(types)`
(`platform/inspecto-entity-store`). Every reader that masks or refuses by class uses it: Risk Score, Anomaly and
Screening evidence, audit, object and notification reads, the assistant's egress, BI in both `typed` and `all`, Link
Analysis (`EntityMasking`, `ReferenceRows`), embed links and `publish.postgres`. The engine cannot see Entity Types,
so it gets the rule through the `MaskedClasses` SPI (`com.gamma.mask`, provided by `SpaceMaskedClasses` in the
control plane; with no provider, the engine's own tests, the four sensitive classes alone hold).

- 🔴 An Entity Type's `masked: false` does **not** unmask a sensitive class: a column classed `MSISDN` masks whatever the
  `msisdn` type says. A type whose id or any claimed class is sensitive counts as masked (`EntityTypes.maskedInForce`):
  its ids mask in Link Analysis and every class it claims masks everywhere. Only `maskingMode: none` turns BI and Link
  Analysis masking off for a Space.
- In BI, `typed` and `all` now mask the same columns (only the reported mode and its basis text differ). In Link
  Analysis they still differ: `all` masks every entity id and every non-fractional reference-row value.
- `quality.pii.mask`: its `classifications:` list is the author's, and since 2026-11-15
  (`MASKING-UNION-RESIDUALS-1`) may name any class the Space masks under this rule (e.g. `WALLET` where a masked
  type claims it); a class that would mask nothing is refused at save and at run. Naming an Entity Type's id selects
  every class that type claims (`EntityTypes.classesSelectedBy`, 2026-11-15); naming a claimed class selects only itself.
- **Which Entity Type a class names** is one helper, `EntityTypes.typeNamedBy` (a type that claims it, else a type
  whose id it is): the save gate, onboarding proposals, Link Analysis typing, discovery, presets, the index and the
  "type still in use" check on retiring a type all use it. `GET /settings/link-analysis` reports each type in force
  with `maskedInForce`, and the Entity Types list shows it.
- ⚠ `publish.postgres` now drops or refuses a column with a masked Entity Type's class (e.g. `WALLET`) unless it is in
  `include_sensitive`, and a four-eyes approval over such a Dataset goes stale and must be given again.

**Shipped configs.** `CONFIDENTIAL` was the only class in the shipped templates outside the vocabulary: 44
columns, all reclassified (8 in `TEMPLATE-CONFIDENTIAL-UNMASKED-1`, 36 with the gate). Identifiers took the
class their template's Datasets already used; cell ids, coordinates, voucher serials and settlement amounts took
`INTERNAL`. `TemplateIdentifierClassificationTest` keeps it so.

## Open rows this concept owns

- ~~**`COMPONENT-KIND-KEY-CENSUS-1`**~~ ✅ **CLOSED 2026-09-16 (`afaa4005c`).** The `widget`/`dashboard`
  top-level key census lives in `ComponentRoutes.validateKind`, the only place it can work — both kinds are
  saved through `POST|PUT /components/{kind}` and never reach `/config/write`, so `AcceptedConfigKeys` stays
  correctly a no-op for them. Accepted = `ConfigSpec` fields ∪ the store envelope (`name`/`owner`/`shares`)
  ∪ a documented parser-only set (e.g. `dashboard.description`, read by `MetadataGraphBuilder` and declared
  by no spec) ∪ the `x-` extension marker. ⛔ It had to be written explicitly: `ConfigLoader.validate` walks
  DECLARED fields only and never emits an unknown-key finding, so copying the `schema` branch's idiom would
  have shipped a gate that refuses nothing.

- ~~**`COMPONENT-BULK-WRITERS-UNGATED-1`**~~ ✅ **CLOSED 2026-09-17** (bundle half `a0f82161d`, templates
  half `6708c0baf`) — see the as-built section below.
- ~~**`PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1`**~~ ✅ **CLOSED 2026-09-16 (`45eff375e`).** The dead top-level
  `version:` was stripped from all 36 committed `*_pipeline.toon` samples (24 under `spaces/`, 12 under
  `inspecto/examples/`) — nothing reads a pipeline's `version`. ⛔ The guard was the deliverable:
  `PipelineKeyCoverageContractTest` now WALKS both trees for *"no committed config regresses"* (rather than
  hardcoding paths, which would miss a new sample), with a falsification test so an empty sweep fails.

- ~~**`EXPECTATION-SPEC-STALE-VS-CONDITION-1`**~~ ✅ **CLOSED 2026-09-17.** `ConfigSpecs.expectation()` now
  declares `when` as `FieldType.MAP` — the `widget.controls` / `dashboard.filter` precedent, which
  validates the envelope and leaves the tree to the parser — and lists `condition` in its `kind` enum.
  🔴 **It was LIVE, not latent, and the row said otherwise.** `POST /validate` and `POST /config/write`
  both resolve `ConfigSpecs.forType("expectation")` from the request body, so the value rules already ran:
  driven over real HTTP, a condition body returned `clean:false` on **two** errors. ⛔ The second was a
  contradiction nobody had named — `column` was declared `required` although `Expectation` exempts the
  `condition` kind, so fixing only `when` and the enum would have left condition expectations refused
  anyway. It moved to a `column-needed-unless-condition` cross-field rule, since `FieldSpec` has no
  conditional-required. No new refusals: a widened enum and an optional field cannot refuse more.

### Both bulk writers now run `validateKind` — `COMPONENT-BULK-WRITERS-UNGATED-1` CLOSED 2026-09-17

`ComponentBundleSource.write` took the gate on 2026-09-16; `BiTemplates.apply` took it on 2026-09-17, in its
**resolve** loop — before any write, so apply stays all-or-nothing and never plants a partial board — mapping
`IllegalArgumentException` to 422 the way `writeComponent` does. ⚠ A bare IAE would have been a **500**: only
`ApiException` maps to a status in `ControlApi`. The two postures differ deliberately: bundle import fails
**per item**, templates fail **whole**.

🔴 **What held the second half open was a false claim recorded in a test javadoc** —
`ControlApiBiTemplatesTest` justified its build-time-only shape with *"the gate cannot be called from here
(`validateKind` is private)"*. It is **package-private** (widened for `BundleRoutes`) and `BiTemplates` is in
that very package. The claim was already false when written; the javadoc now retracts it in place.
⇒ **A stated blocker is a hypothesis too — re-check it before treating it as the reason something is open.**

⚠ **Severity is defence-in-depth, established BY CONSTRUCTION rather than by reading the accepted-set:**
`substituteTree` copies keys verbatim and `substituteAny` rewrites only String *values*, and every key
originates in a hardcoded `Map.of(...)` — so `dataset`/`prefix` cannot introduce a top-level key and a KEY
census cannot fire on today's templates. ⛔ **No new test was added, deliberately:** with no reachable input
able to trip the gate, a new test would be one that CANNOT FAIL. The gate was mutation-proven instead (bogus
key ⇒ 422, nothing written; gate removed ⇒ 200 and all four components planted), and the build-time
`everyTemplateWritesABodyTheAuthoringRouteAccepts` remains the guard that can actually go red.

⇒ Open row: **`BITEMPLATES-GATE-ORDER-1`** — `apply` checks the 409 conflict BEFORE the 422 spec gate,
inverting the `endpoint` skill's order. Observable only when a template is both conflicting and invalid,
which no curated template can be today.

### `BUNDLE-ESCAPE-TEST-IS-ENVIRONMENT-DEPENDENT-1` (open, P1, filed 2026-09-17)

`ControlApiBundleNewKindsTest.jobImportRefusesAnEscapingPathValueWithoutAbortingTheBatch` is the only guard
that a bundle cannot plant a job config whose `dir` escapes the Space. It **fails in a clean detached worktree**
at `c62b46a4` and at `218d0cff` — `failed: 0`, the escaper reported `imported` — while **passing 11/11 in the
main checkout at the same commits**.

⛔ The obvious explanation was wrong and is recorded so nobody re-derives it: a peer's uncommitted work was
NOT the cause. The commit in between (`ad29e683`) touches no bundle, `PathJail` or `ConfigSafetyValidator`
code, and the failure reproduces at a commit that predates it.

🔴 The likely mechanism is **escape depth**: the test uses `@TempDir` and climbs exactly six `..`, so
whether the resolved path lands outside an allowed root depends on how deep the temp directory is on the
machine running it. A security property is being pinned by a test whose verdict depends on filesystem layout,
and a fresh clone — the CI case — is the failing side.

⚠ Two possibilities must be separated before anything is changed: either the **gate** does not refuse the
escape and the main checkout is green by accident (so `JOB-CONFIG-THIRD-PRODUCER-1`'s fix is incomplete), or
the gate holds and only the **test** is fragile. ⛔ Do not make the test green first — that is how a real gate
gets papered over.
