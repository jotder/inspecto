# Config-key contract + vocabulary enforcement

**Status:** IN FLIGHT — opened 2026-08-03. **D1 · D2 · D4 · D5 shipped** (`9a4ff7c7`, pushed 2026-08-03).
**Open:** D3-remainder (the `connection` binding — a UX change), **D6** (unstarted; a new timezone surface
needing design), **D7** (⚠ **re-scoped 2026-08-03** — the flat filter node round-trips fine and is merely
*undeclared*; option 1 ruled out as a regression) and **D8** (both awaiting operator decision), the
§3.2/§3.3 guards, and both renames (§4, §5). Do not archive this plan until those close.
**Trigger:** operator asks, in order — *"match necessary configs/naming with UI/pipeline config … UI looks
different, need to same that saves to pipeline and execute engine use it"*, then *"remove flow from
everywhere, use pipeline. create a common checking point, validation layer"*, then *"remove Cube, use
Matrix"*.

**Scope decisions taken by the operator (2026-08-03):** the Flow→Pipeline sweep goes to **all three tiers,
including breaking on-disk/config-key/JSON changes**; the validation layer covers **all four surfaces**
(Java+TS source · TOON config keys · OKF+superpower docs · the UI↔config↔engine name contract).

---

## 0. Why the order below is not the order the asks arrived in

The audit that preceded this plan found **six live key-contract defects**, not naming preferences. The
worst — a `transform.filter` authored in the UI writes `predicate` while the engine reads `where` — means
**filters authored in the node dialog silently no-op**. Renaming `Flow`→`Pipeline` across on-disk
directories while UI and engine disagree about what a filter's config key is called would be polishing a
foundation that leaks.

So: **fix the contract (§2) and land the guard that prevents recurrence (§3) before the cosmetic renames
(§4, §5).** The renames are agreed; only their position in the queue is being argued.

## 1. Root cause: there is no single source of truth for node cfg keys

| Layer | Where cfg keys are defined | Enforced? |
|---|---|---|
| UI | `inspecto-ui/.../pipelines/node-attributes.ts` (`NODE_ATTRIBUTES`), twin at `catalog/onboarding/stage-attributes.ts` | no |
| Wire | `AttributeSpec.key` written **verbatim** — `config[s.key] = val` (`node-config.dialog.ts:439-449`) | no |
| Server catalog | `GET /pipelines/node-types` → `type, category, label, description, accepts, emits, emitsNamedRoutes` — **carries no attribute specs** | n/a |
| Engine | `RowShaper` / `PipelineLift` / `PipelineCompiler` read cfg keys directly | — |

Two facts make drift inevitable:

- **There is no case-conversion or mapping layer at all.** Zero `HttpInterceptor` implementations, zero
  `toSnake`/`camelize` helpers in `inspecto-ui/src/app`. The only transform is
  `inspecto/component-model/flat-keys.ts` (`KEY_SEP = '__'`, `flattenBlock`/`nestKeys`). **Every UI spec
  `key` must already be the exact backend key** — nothing catches it when it isn't.
- **Per-node specs live only client-side.** The server serves a node-type catalog with no cfg vocabulary,
  so the engine's expectations are never published and never checked.

`AttributeSpec` (`inspecto-ui/.../component-model/attribute-spec.ts:32-58`) is
`{key, label, type, tier, required?, default?, options?, pattern?, min?, max?, dependsOn?, help?,
placeholder?}` — note **`key` is the config key**, `label` is display-only, and there is no `name` field.

## 2. The six defects (fix first)

| # | Defect | Evidence | Effect |
|---|---|---|---|
| **D1** | `transform.filter` — UI writes `predicate`, engine reads `where` | `node-attributes.ts:46` vs `RowShaper.java:79` (and `:261` fused path; `"predicate"` at `:89` is only an error label) | **filters authored in the dialog no-op** — ✅ key renamed 2026-08-03, but the no-op **persists**: see D7 |
| **D2** | `transform.route` — UI `route_column` is read by nothing | `node-attributes.ts:50` vs `RowShaper.java:99-141` (reads only `branches[]{key,where}`, `mode`, `default`) | dead control; its own help text admits routes live on edges — ✅ **FIXED 2026-08-03**: attribute removed (`mode` kept, it is engine-real via `RowShaper.java:104` + `ConservationCheck.java:78`); the edge-authoring guidance moved onto `mode`'s help so it wasn't lost; also stripped from 2 seeds |
| **D3** | `acquisition` reuses `COLLECTOR_ATTRIBUTES` — the `collector:` TOON block keys, not acquisition-node cfg | six sub-mismatches, below | acquisition nodes mis-author across the board |
| **D4** | generic node dialog never bridged flat spec keys ↔ nested config — **in BOTH directions** | `node-config.dialog.ts:439-449` (save) **and** the constructor's split (load); enrichment path *does* (`:496`), onboarding panes do (`collection-pane:265`, `publish-pane:93`, `parsing-pane:298`) | ✅ **FIXED 2026-08-03.** Save wrote `__` keys literally (`duplicate__mode`, read by nothing); load compared *raw* top-level keys to *flat* spec keys, so a real `duplicate: {mode}` block matched no spec, fell into free-form as a JSON **string**, and — free-form being applied last — **overwrote the schema form's own value on save**. The load half was found only because the save-half regression test failed on it |
| **D5** | `partition_by` is a phantom key | mock seeds `pipeline-case-studies.seed.ts:50`, `telecom-ra.seed.ts:44`, `default-space.seed.ts:325` | matches no backend key; real partitioning is schema-level `partitionKey`/`partitions[]` — ✅ **FIXED 2026-08-03**: it had spread to **five** seed files (also `financial-audit`, `link-analysis`, and a 2nd case study), all stripped; already correctly absent from the UI specs. New guard `mock/seeds/seeded-node-config.spec.ts` stops it coming back |
| **D6** | no timezone surface in the UI while the backend specs `meta.domain.timezone` | zero `timezone\|timeZone\|zoneId` hits in `inspecto-ui/src` | unmodeled config surface (not a rename) |
| **D7** | `transform.filter` on the flat path has a **real, round-tripping vocabulary that the UI does not declare** — the spec declares the *authored-graph* one instead (⚠ re-scoped 2026-08-03, see below; the original "unrunnable" framing was wrong) | `PipelineLift.java:180-186,242-251` **emits** a `transform.filter` node carrying `filter_target_column`/`include_prefixes`/`include_regex`/`exclude_prefixes`/`exclude_regex`; `PipelineEditable.java:277` lowers it back into `processing.csv_settings`; `PipelineConfigParser.java:255-259` reads exactly those keys. The spec's `where` belongs to `RowShaper.java:79`, reachable only from an authored `*_flow.toon` via `PipelineJobRunner.java:126-135` (writes 405 since W5) | a dialog-authored `where` no-ops, **and** the filtering that *does* work here is unreachable from the UI |

| **D8** | `sink.materialized` upsert config is **misnamed**, not phantom | seeds `pipeline-case-studies.seed.ts:51,93` carry `mode: 'upsert'` + `key_columns` (0 Java readers), but the real capability is pipeline-level `reference: {load: upsert, key: [...]}` (`PipelineConfigParser.java:406-412`, `Load.from`, `strList`) | the demo narrative is implementable but unspelled; a naive "delete the dead key" sweep would destroy it |

**D8 — found 2026-08-03 by the new seed guard, and it is the counter-example to D2/D5.** The guard flagged
`key_columns`, I deleted it, and `pipeline-case-studies.spec.ts` failed on *"upserts candidates by key"* — a
**documented** invariant (`docs/superpower/pipeline-case-studies.md`). Zero readers turned out to mean
*misnamed*, not *fictional*: upsert-by-key is real, just pipeline-level under `reference.load`/`reference.key`
for a `produces: reference` store, rather than node-level `mode`/`key_columns`. **The deletion was reverted**
and the key was left in place deliberately. ⚠ The rule this establishes, now pinned in the guard's own
docblock: before deleting a zero-reader key, check for a **differently-named equivalent** — otherwise a
key-hygiene sweep silently removes a capability someone documented. Fixing D8 means renaming the seed to the
engine's shape (and deciding whether upsert belongs on the node at all, since `sink.materialized` is not in
`LOWERABLE` and cannot round-trip a flat config) — a modeling decision, left open.

Also confirmed while verifying D2/D5, so a future sweep does not get it wrong: `mode` is engine-real for
`transform.route` (`RowShaper.java:104`, `ConservationCheck.java:78`) and `table` is round-tripped for
`sink.persistent` (`PipelineEditable.java:149`) — so **neither may go in a global dead-key list**, even though
both are dead on other node types. Deadness is per node type.

**D7 — found while verifying D1 in the preview (2026-08-03).** Renaming `predicate`→`where` was necessary
but not sufficient. Added a `Filter` node to `cdr_ingest` in Edit mode, set `amount > 0`, saved, and read
the persisted config back:

```
processing.csv_settings:
  include_regex: ["msisdn NOT LIKE '0000%'"]
  where: "amount > 0"          ← written by the dialog, read by nothing
```

The palette offers `Filter` for a flat pipeline, `lower` accepts the node without a refusal, and the value
lands in a map whose reader has no `where` key. **`include_regex` is not the same capability** — those are
regexes matched with `regexp_matches()` against ONE raw physical column *before* parsing
(`DuckDbCsvIngester.filterWhere:713-730`, inlined into the `read_csv` SELECT at `:140-141`), so `amount > 0`
is inexpressible as one.

### ⚠ D7 re-scoped 2026-08-03 — the flat filter node is NOT broken, it is UNDECLARED

Verifying the option set overturned its premise. **`transform.filter` already round-trips correctly on the
flat path** with its own vocabulary: `PipelineLift.java:180-186` synthesises a `"Row filter"` node whenever
`cfg.csv().hasRowFilters()`, populating it from `filterConfig` (`:242-251`) with `filter_target_column` +
`include_prefixes`/`include_regex`/`exclude_prefixes`/`exclude_regex` — precisely the keys
`PipelineEditable.java:277` lowers back into `csv_settings` and `PipelineConfigParser.java:255-259` reads.

So the flat path has **two distinct filtering moments**, and the UI declares neither correctly:

| Moment | Vocabulary | Status |
|---|---|---|
| **pre-parse**, regex over one raw physical column | `filter_target_column`, `include_*`/`exclude_*` | **works and round-trips today** — but no `AttributeSpec` declares it, so it is unreachable from the dialog and invisible on a lifted node |
| **post-parse**, SQL predicate over parsed columns | `where` | **does not exist** in the flat runtime; only `RowShaper` (authored graph, writes 405) honours it |

D7 is therefore the **same category as D8** — a mis-declared vocabulary, not a missing capability — except
the two vocabularies are genuinely different capabilities, so it is not a pure rename.

**⛔ Option 1 (refuse) is now ruled out — it would cause a regression.** Lift *emits* `transform.filter`
for every pipeline that has row filters configured, so an `UNSUPPORTED_NODE` refusal in `lower` would break
open→save on exactly those pipelines. The `transform.map` objection also dissolves: map's lifted config is
only `{schema: …}` (`PipelineLift.java:189-192`), **derived** from the pipeline schema and regenerated on
every lift, so dropping it in `lower` is lossless — not the same situation as filter. (The `:209` comment's
"companion-persisted" half refers to `enrichment`; the `_enrich*` files are audit ledgers, not a node-config
companion.)

The two live options, no longer mutually exclusive:

2. **Give the flat path a real SQL row predicate** — a new `csv_settings.where` honoured post-parse. Cost
   is now measured and small: `PipelineConfigParser.java:255-259` (+1 line) → `PipelineConfig.Builder`
   (`PipelineConfig.java:919`, **package-private**, free to change) → `PipelineConfig.CsvSettings`
   (`:90`, `@PublicApi(since="2.0.0")` record — an added component changes canonical-constructor arity;
   in-repo precedent at `:83-88` treats additive optional components as non-breaking) →
   `DataTransformer.materialize` (`DataTransformer.java:53-99`), the existing post-parse seam that builds
   `CREATE TABLE "<dest>" AS SELECT …`. **~10 lines, no new pipeline stage.** Wrap the built select as a
   derived table (`… AS SELECT * FROM (<select>) t WHERE <pred>`) — target-column aliases cannot be
   referenced from the same SELECT's `WHERE`.
3. **Declare the pre-parse vocabulary the flat path already honours.** No longer "not recommended" — the
   original objection assumed this meant reusing a name for different semantics, but these keys *are* this
   representation's real, already-round-tripping contract, and leaving them undeclared is what hides a
   working feature. Blocker stands and is UI-side: **`AttributeSpec` has no list type**, needed for the
   four `include_*`/`exclude_*` lists. Distinct labels/help must make the pre- vs post-parse difference
   explicit if both ship.

**Minimum honest fix is 3** (surface what already works). **2 is additive** and only needed if a SQL
predicate over parsed columns is actually wanted.

The generalisable lesson, and why §3.3 must be built as specified: a key-name check alone would have
called D1 green. The bidirectional assertion has to be **per representation** — "declared for a node type
the flat lower accepts" vs "read by the runtime that actually executes that file" — or it will keep
blessing keys that are spelled right and reachable by nothing.

⚠ **D3 was largely WRONG — re-verified 2026-08-03 against the reachable path, and it collapses into D4.**
The detail table below cites `PipelineLift`/`PipelineCompiler`, i.e. the **authored-graph** path — the same
mistake as D1/D7. The dialog saves to the flat `*_pipeline.toon`, whose lower copies acquisition node cfg
straight into the `collector:` block (`PipelineEditable.lower`), and that block's parser reads:

| Key | Flat parser | Verdict on `COLLECTOR_ATTRIBUTES` |
|---|---|---|
| `include` / `exclude` | `strList(src.get("include"))`, `…("exclude")` — **singular**, and `strList` accepts **either a list or a comma-string** (`PipelineConfigParser.java:438,440,691-700`) | ✅ **correct as-is** — the plural `includes`/`excludes` claim is the authored path only |
| `duplicate__*`, `stability__*`, `post_action__*` | nested **maps** `duplicate` / `stability` / `post_action` (`:450,459,518`) | ✅ correct **once `nestKeys` runs** — this is exactly D4, not a key-name defect |
| `guarantee` | `Guarantee.from(opt(src, "guarantee", null))` — a plain **string** (`:474`) | ✅ correct as-is — the "typed, `instanceof` drops a string" claim is the authored path only |
| `recursive_depth` | `toInt(src.get("recursive_depth"))` (`:441`) | ✅ correct as-is |
| `connection` | read as a plain key (`:451`), **but** both lift and lower strip it — *"connection is carried on `use:` … never mirrored in config"* (`PipelineEditable.java:124-125`, and `:247-249` re-derives it from `use`) | ⛔ **the one real defect** — silently discarded on every acquisition-node save |

**So D3 reduces to the `connection` attribute alone.** Everything else it alleged is either correct already
or is D4. Fixing D4 alone makes `duplicate`, `stability`, `post_action`, `include` and `exclude` all land in
the shape the flat parser reads. ✅ **D4 done 2026-08-03** (`node-config.dialog.ts` generic `save()` now runs
`nestKeys`, with a regression test).

⚠ **D3-remainder is NOT a one-line deletion — do not just drop the attribute.** `bindKindFor('SOURCE')` is
`null` (`pipeline-graph.spec.ts:207`), so the acquisition dialog renders **no** Connection picker and falls
back to a free-text `use` box. The discarded `connection` attribute is therefore the *only discoverable* way
to set a connection on the node; deleting it would leave the operator hand-typing `connection/<name>` into a
free-text field. The fix is to give acquisition a real binding — `bindKindFor('SOURCE') → 'connection'` with
the existing autocomplete writing `use: connection/<name>` — **and then** drop the cfg attribute. Note the
attribute must stay in the shared `COLLECTOR_ATTRIBUTES` for **Onboarding**, which authors the `collector:`
block directly and for which `connection` *is* a real key; so this is a per-adopter exclusion (a documented
derivation of the shared table), **not** a fork and not an edit to the shared table. Small, but a UX change
rather than a key fix — left open deliberately.

<details><summary>Original D3 detail table (authored-graph path — kept for provenance, do not act on it
without first confirming which representation the surface saves to)</summary>

**D3 in detail** — `COLLECTOR_ATTRIBUTES` keys vs actual node cfg:

| UI key | Reality |
|---|---|
| `include` / `exclude` | node cfg is **`includes`/`excludes`**, and **lists** not comma-strings (`PipelineLift.java:113-114`, `PipelineCompiler.java:227-228`) |
| `connection` | on a node this is **`use: "connection/<name>"`**, not cfg (`PipelineLift.java:126`) |
| `duplicate__mode`, `duplicate__on_change` | `duplicate` lives on the **fingerprint-dedup node**, not acquisition (`PipelineCompiler.java:287-296`) |
| `stability__window` | node cfg `stability` is a **typed `PipelineConfig.Stability`** — a string is dropped by the `instanceof` check (`PipelineCompiler.java:234`) |
| `post_action__on_success`, `post_action__archive_path` | typed `PostActionConfig` (`PipelineCompiler.java:278`) |
| `guarantee` | typed `PipelineConfig.Guarantee`; a raw string fails `instanceof` (`:245`) |

</details>

**Each defect needs a regression test that asserts the round trip** — author via the dialog's save path,
lift/compile, assert the engine reads the value. Fixing the key without the test just resets the clock.

## 3. The common checking point (all four surfaces)

### 3.1 Serve the cfg vocabulary from the server — the structural fix

The durable fix for §1 is to **publish attribute specs from the server** so one definition feeds the UI
form, the validator, and the engine. Two candidate homes, both existing:

- **`ConfigSpecs`** (`inspecto-config/.../config/spec/ConfigSpecs.java`) — already the declarative field
  spec for `pipeline, enrichment, job, schema, meta, alert, expectation, widget, dashboard`, already served
  at `GET /config/spec/{type}`. Field paths are dotted strings matching TOON nesting exactly.
- **`PipelineNodeType`** — extend the `GET /pipelines/node-types` payload with per-type `attributes[]`.

Recommendation: extend `PipelineNodeType`, because node cfg is per-node-type and `ConfigSpecs` is
per-config-file. Then `node-attributes.ts` becomes a *fallback* for offline/mock only, and a CI check
asserts the client table matches the served one.

### 3.2 Extend `tools/check-vocabulary.mjs`

The guard exists and is CI-wired (`.github/workflows/ci.yml:29`) with rules `measure-threshold`,
`data-store`, `bare-flow`, `source-acquisition-entity`, scanning **9 curated user-facing docs**.

Its header states the narrow scope is deliberate: *"Banning those words everywhere would be false-positive
noise, **and a noisy guard gets disabled**."* That warning is correct and must survive the expansion —
each new surface needs an allowlist for deliberate keeps, or CI goes red on intentional names.

| Surface | Approach | Allowlist needed for |
|---|---|---|
| **TOON config keys** | scan `spaces/**/config/*.toon`, `examples/**/*.toon` for banned **keys** (not prose) | none — config keys should be pristine. **Do this one first**: cheapest, highest value, catches a bad key before data exists |
| **OKF + superpower docs** | add trees, exclude `docs/archived-documents/**` | design docs legitimately name internal types — allowlist `FlowGraph`-style IR names |
| **Java + TS source** | identifier scan | large allowlist; land **last** |
| **UI↔config↔engine contract** | separate check, not a vocabulary rule — see §3.3 |

`docs/archived-documents/**` is excluded permanently: CLAUDE.md defines it as *"kept for provenance, never
maintained"*. Rewriting history there is wrong, and it is most of the 1,791 raw `flow` hits.

### 3.3 The name-contract check (new, separate from the docs guard)

Assert per node type that **UI spec key == TOON cfg key == the key the engine reads**. This is the check
that would have caught all of D1–D5 at authoring time. It belongs with `ConfigSpecs`/`PipelineNodeType`,
not with the prose guard — different failure mode, different owner, different allowlist.

Cheapest first version: a test that walks the served node-type attribute specs and asserts every key is
read somewhere in `PipelineLift`/`PipelineCompiler`/`RowShaper`, and that every key those read is declared.
Bidirectional — D2 and D5 are "declared but never read", D1 is "read but declared under another name".

⚠ **Per representation, not per key name** (learned from D7). "Read somewhere in
`PipelineLift`/`PipelineCompiler`/`RowShaper`" is too weak a right-hand side: `where` satisfies it via
`RowShaper` while being unreachable from the flat `*_pipeline.toon` the editor writes. The assertion must
bind a node type to **the runtime that executes the file the editor saves** — otherwise the check greenlights
a correctly-spelled key that nothing on the reachable path reads, which is precisely how D1 looked fixed.

## 4. Flow → Pipeline, all three tiers

GLOSSARY §13 records Flow→Pipeline as ✅ DONE with *"Kept: authored-flow storage dir `flows/` + JSON
response keys."* This plan **reverses those keeps** per the operator's Tier-3 decision. The §13 row must be
updated to say so, or the next shift will read the keeps as still-current.

**Tier 1 — non-breaking, pure refactor.** `flowId` params/locals, `runningFlows()`, `triggerFlowRun()`,
`ProvenanceRow.flowId`, and test classes `ControlApiFlowsTest` / `ControlApiFlowRunTest` /
`ControlApiFlowCrudTest`. Compile-checked, no migration.

**Tier 2 — breaking, internal.**

- `inspecto_flow_provenance` → `inspecto_pipeline_provenance` (opt-in projection, `-Dprovenance.backend=duckdb`).
- `FLOW_CONSERVATION_IMBALANCE` → `PIPELINE_CONSERVATION_IMBALANCE`. ⚠ **This value is persisted in
  existing event-ledger rows.** Renaming the constant without a read-alias orphans historical Incidents and
  any saved query filtering on it. Alias on read, do not rewrite history.

**Tier 3 — breaking, external contract.**

- `spaces/*/config/flows/` → `pipelines/` (live — `spaces/default/config/flows/` exists). Needs a boot-time
  migration or dual-read.
- `flow:` → `pipeline:` in `*_job.toon` (`type: pipeline` jobs). Existing configs stop parsing without a
  dual-read fallback; the key is described in `okf/backend/pipeline-graph/live-execution.md` as *"the
  `flow:` key name is verbatim legacy"*.
- JSON response keys.

Tier 3 requires a version bump per `docs/BRANCHING.md` and **must go through the `release-workflow`
skill**. Recommended sequencing: Tier 1 in its own commit; Tier 2 with the read-alias and a test proving
old rows still resolve; Tier 3 last, with the migration and dual-read, as one coordinated change.

## 5. Cube → Matrix

Smaller and mostly already correct. GLOSSARY §13 marks this row **unstarted** and describes it as an
**"additive label, not a model rename"** — the model type stays `Derived Table` / `NodeKind.DERIVED_TABLE`,
and *"⛔ 'Cube' stays a **verb** (the Transform action that produces it), never the asset's noun."*

So the sweep is narrow, and most current hits are **legitimate**:

| Hit | Verdict |
|---|---|
| `heroicons_outline:cube` icon ids (both `public/icons/` and `src/assets/icons/`, `link-analysis-toolbox`, `pipeline-graph.ts:352`, `a2ui-render`, `heroicons-outline-ids.ts`, `SpaceManager.java:230`) | **keep** — third-party icon names, not vocabulary |
| `GLOSSARY.md:270` *"aggregates (cubes) data"*, `:314` *"a Transform or cube/rollup"* | **keep** — the verb sense, explicitly sanctioned |
| `USER_GUIDE.md:50` *"Transform / cube (runs IN the lakehouse)"*, `:460` *"materialized by a Transform or cube"* | **keep** — verb sense |

**No noun-sense "Cube" was found in the UI at all**, consistent with §6-B's note that Matrix is *"not yet
surfaced in the UI as of 2026-07-20."* So the real work is **additive**: introduce the **Matrix** label in
Catalog/Studio where a summary Derived Table is displayed, then mark the §13 row ✅. A `cube-as-noun` guard
rule is cheap but must exempt the icon ids and the verb sense — otherwise it is exactly the noisy guard the
existing header warns about.

## 6. Sequence

1. **D1** (`predicate`→`where`) — ✅ done 2026-08-03 (name pinned by a spec test). The round-trip test is
   deferred to **D7**, which is what actually makes filters do nothing; D7 needs a decision before code.
2. **D4** (flat ↔ nested bridge in the generic dialog, **both directions**) — ✅ done 2026-08-03 with two
   regression tests. Save runs `nestKeys` then deep-merges each root over the node's prior config
   (`mergeBlock`), so engine-read sub-keys with no `AttributeSpec` — `duplicate.algorithm`,
   `stability.size_checks`/`ready_marker`/`exclude_temp_patterns`, `post_action.tags`/`on_unsupported`
   (`PipelineConfigParser.java:449-470,516-527`) — survive a guided save instead of being dropped.
   **D3 collapsed into it**:
   re-verification showed `COLLECTOR_ATTRIBUTES` is correct for the flat path except for `connection`, so
   the "acquisition gets its own attribute table" step is **cancelled** — all that remains of D3 is dropping
   the `connection` attribute. See the ⚠ above §2's detail table.
3. **D2**, **D5** — ✅ done 2026-08-03, with a new seed-level guard
   (`mock/seeds/seeded-node-config.spec.ts`) so the phantoms cannot re-spread. Turned up **D8** (open).
4. **§3.3** name-contract check, bidirectional. Locks in 1–3.
5. **§3.2** guard on TOON config keys.
6. **Flow Tier 1.**
7. **§3.2** guard on OKF + superpower docs; reconcile
   [`consignment-elt-architecture.md`](consignment-elt-architecture.md) against GLOSSARY (§7 below).
8. **Cube → Matrix** additive labels; mark §13 ✅.
9. **Flow Tier 2** with read-alias.
10. **Flow Tier 3** with migration — via `release-workflow`, version bump.
11. **§3.2** guard on Java + TS source, with allowlist. Last, largest allowlist.
12. **§3.1** serve cfg vocabulary from the server — the structural fix. Can start any time after 4;
    sequenced last because it is the largest and 4 already prevents regression.

## 7. Owed: reconcile the ELT design doc against GLOSSARY

[`consignment-elt-architecture.md`](consignment-elt-architecture.md) was written before this audit and
diverges from canon in five ways. **Four still to apply** — the Batch row below is now resolved the other way.

| Doc says | Canon | Note |
|---|---|---|
| ⛔ *Batch* → **Consignment** (§3) | ✅ **RESOLVED 2026-08-03 in the doc's favour — Consignment is now canonical.** GLOSSARY §2 redefines the entity as **Consignment**, §6-A is now **Run ⊇ Consignment ⊇ File**, and §13 carries the Batch→Consignment row | User's call, taken knowing the scale: **517 Java files / 39 `@PublicApi` types**, so it is a *breaking* rename needing a version bump, and §13 now names it the save-for-last row. ⚠ Two follow-ons the doc must adopt: identity is **`(consignment_id, run_id)`** — Run is already canonical for "one execution", so a reprocess is a new Run over the same Consignment, not the invented `attempt`; and the rename is **scoped by concept, not by string** — the generic grouping sense (`batch_max_files`, `BatchedOperations`, JDBC batching) stays `batch`. ⚠ My original §3 recommendation of this rename was made **without checking GLOSSARY** and was wrong *as a proposal*; it is right only as a deliberate, version-bumped rename, which is what it now is |
| "Processor blocks" (§4) | **Step** — GLOSSARY §5, *"drawn from the closed `BuiltinNodeType` vocabulary (20 ids)"* | NiFi analogy is fine as explanation; the term is Step |
| proposes new ingress node types | **`acquisition` and `adapter` already exist** — confirmed in `BuiltinNodeType`: `acquisition, adapter, parser, transform.{map,filter,select,derive,validate,dedup.marker,dedup.fingerprint,route,split,merge}, enrichment, sink.{persistent,materialized,view}, alert, gap, event` | Collector→Decoder→EL likely maps onto `acquisition`→`adapter`→`parser`. The set is *"⚠ Closed on purpose"* |
| batch-terminal processors as graph nodes (§4 tier 7) | **in-motion (Pipeline) vs at-rest (Job) is a binding line** (`pipeline-graph-design.md` §3.8) — *"an at-rest operator cannot be an in-motion node"* | Summary/reconciliation must be **Jobs on `on_commit`**, not Steps. Also resolves the doc's suggestion to revisit `ON_COMMIT_SAME_GRAPH` — that refusal **enforces** this line and should stay |
| "summary table" (§7) | **Derived Table** (model) / **Matrix** (user-facing label) — §6-B | |

Plus a real alignment win the doc should adopt: **a partition-deriving field already exists.**
`partitionKey` (top-level, camelCase, **legacy**) and the current `partitions[]` list of
`{column, source, type}` where `type ∈ VARCHAR|DOUBLE|INTEGER|DATE_YEAR|DATE_MONTH|DATE_DAY`
(`PartitionDef.java:9-25,62-102`; a lone `partitionKey` synthesises year/month/day defs at `:93-100`).
**No `timestamp`/`event_time`/`time_field`/`date_field` key exists anywhere.** So the doc's §10 event-time
field should bind to `partitions[].source` with a `DATE_*` type rather than inventing a key — and the UI
already has exactly one picker for it ("Partition key (optional)",
`schema-mapping-pane.component.ts:404`).

## 8. Known naming inconsistencies (context for the guard's allowlists)

- **Pipeline TOON is snake_case; schema TOON is camelCase** — `dirs.status_dir`, `schema_file`,
  `marker_extension`, `post_action.on_success` vs `partitionKey`, `canonicalName`, `rawName`,
  `targetColumn`, `sourceExpression`.
- **Same concept, two spellings:** `alert.onPipeline` vs `job.on_pipeline` (both in `ConfigSpecs`).
- **CSV parsing has two live surfaces:** legacy `processing.csv_settings` (validated by
  `ConfigSafetyValidator:52-55`) vs the top-level `parsing:` block actually used in both live samples
  (`PipelineConfigParser.java:227-230`).
- **camelCase strays in otherwise snake_case specs:** `datasetId`, `refDataset`, `refColumn`, `queryId`,
  `targetType`.
- **Java record fields are camelCase, TOON keys snake_case** (`logDir`↔`log_dir`,
  `filterTargetColumn`↔`filter_target_column`) — an intentional, consistent translation. **The seam always
  case-flips**; that is not a defect and must be allowlisted.
- **Signal types have no grammar:** `pipeline.batch.committed` (3-seg) · `expectation.violated` (2-seg) ·
  `decision-rule.applied` (hyphenated) · `maintenance.backup.verify_failed` (snake verb) — and
  `pipeline.commit` is a *different* signal from `pipeline.batch.committed`. `Signal.type` is free-form,
  not an enum. `EventType` by contrast is a clean SCREAMING_SNAKE closed set. **Pick one signal grammar and
  add a constants class** before adding the ~10 new types the ELT design needs.
- **`collector:` block internals still say "source"** in `PipelineConfig` record docs (`source.connection`,
  `source.duplicate`, `source.post_action`) — the Collector-rename residual already tracked in BACKLOG.
