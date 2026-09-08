# Adjudication — docs-consolidation-plan §5.8.1, Group 4 rows 4.4–4.6 + Group 5 rows 5.1–5.7

Read-only pass. Every verdict grounded against the tree at `master` (HEAD `db2b24c0`), 2026-09-08.

---

## 0. Counts established (the load-bearing numbers)

### 0.1 Processor catalog size — **119**

`inspecto-engine/src/main/java/com/gamma/pipeline/ProcessorCatalog.java`, `PROCESSORS` list
(`:60`–`:186`): **119** `p("…")` entries.

Per family (sums to 119): ACQ 20 · PRS 17 · DQ 14 · XFM 21 · BI 14 · ENR 10 · CTL 8 · SNK 15.

### 0.2 Status split — **34 DELIVERED / 18 PARTIAL / 67 PLANNED**

Counted from the `p(` entry lines only. 34 + 18 + 67 = 119. ✓

🔴 **This is where every wrong doc number came from.** A naive
`grep -c "Status\.PLANNED" ProcessorCatalog.java` returns **69**, because the static-block invariant
contains two more occurrences that are not catalog entries:

- `ProcessorCatalog.java:188` — `if (p.status() == Status.PLANNED && (p.nodeType() != null || p.capability() != null))`
- `ProcessorCatalog.java:190` — `if (p.status() != Status.PLANNED && p.nodeType() == null && p.capability() == null)`

So "69 planned" is a grep artefact of the guard that protects the field, off by exactly those two lines.
Both docs and `BACKLOG.md` carry it.

Per family, statuses from the catalog:

| Family | delivered | partial | planned | total |
|---|---|---|---|---|
| ACQ | 4 | **5** | **11** | 20 |
| PRS | 7 | 1 | 9 | 17 |
| DQ | 5 | 2 | 7 | 14 |
| XFM | 6 | 1 | 14 | 21 |
| BI | 0 | 3 | 11 | 14 |
| ENR | 1 | 1 | 8 | 10 |
| CTL | 6 | 2 | 0 | 8 |
| SNK | 5 | 3 | 7 | 15 |
| **Total** | **34** | **18** | **67** | **119** |

Only **ACQ** disagrees with `step-catalog.md`. Every other family heading in that page matches the
catalog exactly.

### 0.3 Step kinds — **16**

- `inspecto-ui/src/app/inspecto/contracts/step-types.contract.json` — **16** entries.
- `PipelineProjection.RECIPE_VERBS` (`inspecto-engine/src/main/java/com/gamma/pipeline/PipelineProjection.java:114`–`:137`) — **16** entries, and they correspond 1:1.

The 16, in order: `collect`→`acquisition` · `parse`→`parser.delimited` · `parser.fixedwidth` ·
`parser.json` · `parser.text_regex` · `parser.xlsx` · `parser.asn1` · `parser.plugin` ·
`dedup`→`transform.dedup` · `transform`→`transform.filter` · `transform`→`transform.join` ·
`sql`→`transform.sql` · `lookup`→`transform.lookup` · `summarize`→`transform.summarize` ·
`route`→`transform.route` · `sink`→`sink.persistent`.

⇒ **no `map`**, and `sql` + `lookup` both present. 9 distinct verbs over 16 entries (a verb repeats per
shape it authors — `PipelineProjection.java:107`–`:113` states that rule).

`PipelineEditable.STEP_KIND` (`:159`–`:166`) carries the 7 chain kinds: FILTER, JOIN, DEDUP, SUMMARIZE,
ROUTE, **LOOKUP**, SQL.

### 0.4 Coverage ratchet — **16 entries = 6 top-level + 10 `processing.*`**

`inspecto-etl/src/test/java/com/gamma/etl/PipelineKeyCoverageContractTest.java`, `UNDECLARED_BLOCKS`
(`:69`–`:90`):

- top-level (6): `active`, `route`, `sinks`, `steps`, `template`, `trigger`
- `processing.*` (10): `dedup`, `disabled_steps`, `duplicate_check`, `ingester_config`, `join`, `map`,
  `mapping_file`, `schemas`, `segments`, `summarize`

`collector` is **not** in the list — it was removed 2026-09-02, and the removal is documented in place:

- `PipelineKeyCoverageContractTest.java:72` — `// "collector" left the list 2026-09-02: CONSIGNMENT-HOME-1 declared collector.consignment.max_files`
- `PipelineKeyCoverageContractTest.java:65-67` (field javadoc) — *"18 when this landed (2026-08-31); **17** after gap 8 declared `output_store` the same day …; **16** after CONSIGNMENT-HOME-1 declared `collector.consignment.max_files` (2026-09-02)."*

`collector` is declared via `ConfigSpecs.java:225` —
`FieldSpec.withDefault("collector.consignment.max_files", "Consignment max files", FieldType.INT, 1, …)`.
`declaredBlocks()` (`PipelineKeyCoverageContractTest.java:205`–`:213`) derives block names from leaf
paths (`a.b.c` declares `a` and `a.b`), so that one leaf declares both `collector` and
`collector.consignment`.

**Census, re-derived by replicating the test's own scan and `declaredBlocks()`:**

| Scope | Blocks the parser reads | Declared in `ConfigSpecs.pipeline()` | Parser-only (ratchet) |
|---|---|---|---|
| Top-level | 18 | **12** | **6** |
| `processing.*` | 24 | 14 | 10 |
| **Total** | **42** | **26** | **16** |

Read top-level (18): `active`, `collector`, `description`, `dirs`, `id`, `name`, `output`,
`output_store`, `parsing`, `processing`, `produces`, `reference`, `route`, `sinks`, `steps`, `stream`,
`template`, `trigger`.
Declared of those (12): all except the 6 ratchet entries.
`processing.*` declared (14) matches the page's own list at `pipeline-config-keys.md:95`–`:98` exactly.

⚠ Method note: `id` is declared with `new FieldSpec("id", …)` (`ConfigSpecs.java:67`), not a static
factory — a regex that only matches `FieldSpec.<factory>(` under-reports it by one and makes top-level
declared look like 11. That is a plausible origin for the page's "11".

---

## 1. `RowShaper` — real location and real line numbers

**Class location: `inspecto-engine/src/main/java/com/gamma/pipeline/exec/RowShaper.java`**, 814 lines,
`package com.gamma.pipeline.exec;` at `:1`. Row 5.6's premise is correct — it is no longer in
`com.gamma.pipeline`.

| Doc citation | Real line | Note |
|---|---|---|
| `RowShaper.java:155-183` (`shape`) | `:140` (3-arg), `:146` (5-arg), `:157`–`:193` (full) | stale |
| `:162-163` (SPI seam) | `:164`–`:165` (`PipelineNodeExecutors.get` / `contributed.isPresent()`) | stale |
| `:164` (join dispatch) | `:166` | stale |
| `:171-173` (project dispatch) | `:177`–`:178` (select/derive), `:181` (sql-as-projection) | stale |
| `:174` (sql branch) | `:180`–`:182` | stale |
| `:442` (`join()`) | `:503` | stale |
| `:455` (`on` missing throw) | `:516`–`:517` | stale |
| `:494-512` (`sql()`) | `:554` | stale |
| lines 46-49 (LEFT JOIN semantics) | the `transform.join` javadoc bullet is `:47`–`:50`; the "LEFT JOIN: every input row survives" phrase is at `:49` | overlaps, off by one at the start |
| `:97` (ReferenceResolver refusal) | `:97`–`:98` — **still correct** | ⚠ |
| `MAP_NODE_CONFIG_KEYS` (no line cited) | `:78` | live |
| `projectionSelectFrom` (no line cited) | `:593` | live |
| `RowShaper.lookup` (no line cited) | dispatch `:168`, method `:206` | live |

🔴 **Row 5.6's word "every" is slightly over-stated**: `RowShaper.java:97` happens to still land on the
`ReferenceResolver.NONE` refusal it cites. The *rule* the row states (treat these numbers as stale, do
not repeat them) survives intact — 9 of 10 are wrong, and the one that is right is right by luck.

---

## 2. Verdicts

### Row 4.4 — `configuration.md` §2, `mapping.rules[]` + `transformType` — **DISPUTED**

**Both halves of the row's evidence are TRUE and verified:**

- `inspecto-etl/src/main/java/com/gamma/etl/DataTransformer.java:266-274` — `recordFields` reads
  `mapping.fields` **first** (`:268`–`:270`, gated on `RecordTransform.isFieldList`), then
  `mapping.rules` as a converted legacy fallback (`:271`–`:272`,
  `RecordTransform.fromMappingRules(...)`).
- `inspecto-etl/src/main/java/com/gamma/etl/TransformCompiler.java:25-27` (field javadoc, verbatim):
  *"The rules themselves no longer compile here — `RecordTransform#fromMappingRules` converts each type
  to its catalog function at read time — but an authoring-time validator (`MappingRules`) still rejects
  a typo against this set."*
  And the class javadoc `:15`–`:18`: *"Until 2026-09-05 this also compiled `mapping.rules[]` … That path
  is gone with `transform.map`."*

**But the row's implied conclusion — that §2 is therefore wrong — is REFUTED by the generators:**

- `inspecto-util/src/main/java/com/gamma/util/SchemaExtractor.java:193` — `mapping.put("rules", rules);`
- `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:335` —
  `out.put("mapping", Map.of("rules", rules));`, with `rule.put("transformType", "DIRECT")` at `:330`.

`configuration.md:160` explicitly frames §2 as *"**Machine-generated** by `create-schema`"*. The
generator still writes `mapping.rules[]` with `transformType`, so §2 is documenting what the tool
actually emits today, not a retired spelling. `transformType` also remains a live validated vocabulary:
`inspecto-engine/src/main/java/com/gamma/pipeline/MappingRules.java:70-73` rejects an unrecognised value
against `TransformCompiler.TRANSFORM_TYPES`, and the `FILENAME_DATE`→`EVENT_DATE`-only refusal at
`MappingRules.java:92-94` is live.

There is **no open BACKLOG row** asking the generator to switch spelling. `RECORD-TRANSFORMER-1` appears
exactly once in `docs/BACKLOG.md` (`:123`), only as absorbed sub-item **(d)** inside Row 15 — the row
itself is gone.

**The decision needed:** should `create-schema`/`suggestSchema` emit `mapping.fields[]` (making §2's
rewrite a consequence), or does the doc keep leading with the legacy-but-still-generated `rules[]` and
label it as such? Until that is answered, rewriting §2 to lead with `fields[]` would describe a file
shape the product does not produce.

**Settled sub-facts inside the row (unambiguous, two stale compiler attributions):**

1. `configuration.md:222` cites `TransformCompiler.direct` — that method **no longer exists**
   (`direct(`/`expr(`/`concatDt(`/`filenameDate(` have zero matches in `TransformCompiler.java`, which
   is now only `TRANSFORM_TYPES` `:30`, `EVENT_TIME_COL` `:40`, `partitionColumn` `:54`/`:64`,
   `dateExpr` `:97`, `eventTimeColumn` `:128`/`:139`). The **rule survives**: the coercion moved to
   `RecordTransform`'s `keep` on an untyped source (`RecordTransform.java:318`,
   `if (KEEP.equals(fn.id()) && !typedSource)`; `DIRECT` → `KEEP` at `:437`).
2. `configuration.md:247` — *"Each rule compiles to one column expression (`etl/TransformCompiler`)"* —
   that path is gone per the javadoc above.

⚠ **The row's line cite `configuration.md:252` is wrong** — `:252` is a blank line. The actual
self-contradiction is at **`:261`–`:262`**: the level-1 cell already says *"a legacy `mapping.rules[]`
row is read as one of these | a row in `mapping.fields[]`"* and the level-2 cell was already corrected
to `RecordTransform` on 2026-09-08. Those two cells now contradict the ~90 lines above them.

---

### Row 4.5 — the ratchet's counts — **UNDISPUTED-DOC**

The page is wrong; the "reportedly 16 / 6 with `collector` declared" reading is right. See §0.4.

Wrong cells / claims in `docs/okf/backend/pipeline-graph/pipeline-config-keys.md`:

| Site | Says | Truth |
|---|---|---|
| `:43` | top-level `18 \| 11 \| 7` | `18 \| 12 \| 6` |
| `:45` | total `42 \| 25 \| 17` | `42 \| 26 \| 16` |
| `:47`–`:48` | history stops at 17; "The 17 current entries" | 16, after CONSIGNMENT-HOME-1 |
| `:72` | `collector` = "parser-only" | declared (via `collector.consignment.max_files`) |
| `:158` | "its size (17)" | 16 |

🔴 The page's `:53`–`:55` is the **correct** half of its own contradiction — it already says
`collector.consignment` "is declared only via `max_files`", which is exactly why `collector` left the
ratchet. `:43`/`:72` are the wrong half.

The `18` and `24` "blocks the parser reads" cells and the `processing.*` row are **correct** — do not
touch them.

---

### Row 4.6 — `#` comments — **UNDISPUTED-DOC**

`inspecto-config/src/main/java/com/gamma/config/io/ConfigCodec.java`:

- `:35`–`:38` — `toMap` = `JToon.decode(toon)`. Javadoc: *"Lenient decode (tolerates `#` comments) — for
  reading existing on-disk configs."*
- `:41`–`:44` — `toMapStrict` = `JToon.decode(toon, STRICT)`. Javadoc: *"Strict decode — rejects
  comments / non-canonical input."*
- `:12`–`:17` (class javadoc) frames it as **footgun G5**: *"shipped configs carry `#` comment lines, and
  `JToon#decode(String)` default decode is **lenient** enough to tolerate them, while a strict consumer
  may not."*

So the plan's reading is exactly right — `toMap` lenient, `toMapStrict` not — and each doc is half-right:

- `toon-config.md:15` ("tolerates `#` comments") and `:54` ("No `#` comments are allowed in files the
  strict parser handles") are **both mechanically accurate**. The defect is what is **missing**: this is
  the file whose `resource:` frontmatter is `ConfigCodec.java`, i.e. the codec's owner, and it records
  the leniency **without the truncation trap**. "Tolerates" is the dangerous word — lenient decode does
  not throw, but it can silently drop the rest of the file.
- `configuration.md:288` (*"The `.toon` format does not support `#` comment lines. Parsing stops at the
  first unrecognised character"*) is **wrong as a mechanism** (the lenient reader accepts them) but its
  **operational rule** — *"do not add inline or standalone comments"* — is correct, and `:289` is why.

⛔ **`configuration.md:289` must survive verbatim** and the edit below does not touch it. It is the
truncation note, and it is the load-bearing fact in this whole row: *"A comment between two top-level
sections makes `JToon.decode` **truncate the file there**, and `PipelineConfig.load` accepts the
truncated map with no error and no warning … (verified 2026-08-18: a comment block in
`spaces/demo/config/orders/orders_pipeline.toon` dropped `output_store:`, `steps:` and `collector:`)."*
Note it names `JToon.decode` — the **lenient** path — which is precisely why "tolerates" needs the
caveat.

---

### Row 5.1 — `transform.lookup` is DELIVERED — **UNDISPUTED-DOC**

`catalog-vs-executors.md` is wrong at **seven** sites. The code is unambiguous:

- `ProcessorCatalog.java` XFM entry: `p("XFM", "transform.lookup", "🗺️", …, Status.DELIVERED,
  "transform.lookup", null, "inline key=value map over one column (2026-09-06); a versioned reference is
  transform.join")` — **DELIVERED**, mapped to its **own** node type, not `transform.join`.
- `BuiltinNodeType.java:109`–`:111` — `TRANSFORM_LOOKUP("transform.lookup", NodeCategory.TRANSFORM,
  "Lookup", "Transcodes one column's values through an inline static map (a CASE); unmatched values pass
  through or take a default.", Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false)`.
- `exec/RowShaper.java:168` — dispatch; `:206` — `private static List<Relation> lookup(...)`; `:195`
  section banner `// ── lookup (inline static map) ──`; `:198`–`:203` javadoc: *"one column transcoded
  through an inline `key=value` list, compiled to a `CASE`"*.
- `PipelineEditable.java:142` — in `LOWERABLE`, `// inline static map → steps: kind lookup (2026-09-06)`.
- `PipelineEditable.java:164` — `STEP_KIND` entry → `PipelineConfig.Step.LOOKUP`.
- `step-types.contract.json` — `lookup` → `transform.lookup`, `lowerable: true`.

`step-catalog.md:145`–`:168` already documents it correctly (*"The first PARTIAL Step Processor
delivered (2026-09-06)"*, `RowShaper.lookup` → `CASE CAST(column AS VARCHAR) WHEN …`, `mappings`,
`target`, `default`, emits `data` only). `step-catalog.md` is the authority here, as the plan says.

Wrong sites in `catalog-vs-executors.md`: `:52` (PARTIAL / `transform.join` / "no inline static map"),
`:159`, `:197` (heading conflates lookup with join), `:199`–`:200` ("no inline literal key→value static
map anywhere in the engine — confirmed by the catalog's own `PARTIAL` status"), `:232`–`:234` (calls
lookup a LEFT JOIN via `RowShaper.join()`), `:240`–`:241` ("the missing 'inline static map' half"),
`:249`–`:251` ("`transform.lookup` looks fully live despite being a partial implementation").

---

### Row 5.2 — RECIPE_VERBS list in `node-types.md` — **UNDISPUTED-DOC**

`node-types.md:165`–`:166` lists `collect · parse · map · dedup · transform→filter · transform→join ·
summarize · route · sink` — includes `map`, omits `sql` and `lookup`. Truth: 16 entries, no `map`
(§0.3). `map` was deleted: `BuiltinNodeType.java:103`–`:105` — *"transform.map was DELETED 2026-09-05"*;
zero hits for `TRANSFORM_MAP` or `"transform.map"` in main Java.

The page **contradicts itself**: `:117` says `transform.map` is DELETED with *"❌ (verb `map` removed)"*
and `:121` says *"✅ recipe verb `sql` since 2026-09-04"*. Its trailing clause at `:166`
(*"`select`, `derive`, `split`, `merge`, `validate` and `dedup.marker` are not offered"*) stays correct.

⚠ **Adjacent same-defect site, not named in the row:** `PipelineProjection.java:71`–`:72` — the
`stepCatalog()` javadoc still says *"`map` authors a `transform.map` node in the GRAPH editor even though
the recipe compiler folds it into parse — the verb exists either way"*. That is a **stale javadoc in the
code**, one file away from the correct `RECIPE_VERBS` list. Not in scope for these rows; worth a
follow-up.

---

### Row 5.3 — catalog size 121 vs 119 — **UNDISPUTED-DOC** (119 is right)

`step-catalog.md` is right (`:4` and `:19` both say 119). `catalog-vs-executors.md:12` says 121.

The plan's explanation checks out arithmetically: the 2026-09-04 fold merged `transform.expression` +
`transform.cast` + `quality.cleanse.trim` into `transform.record` (3 → 1 = −2), so 121 was the pre-fold
count. `catalog-vs-executors.md:40`–`:50` is the very section that documents that fold, while its own
opening line still carries the pre-fold total.

⚠ **Two more live sites carry 121 and are not named in the row:**
- `docs/okf/frontend/features/pipeline-editor.md:121` — *"121 processors, **every one visible**"*
- `docs/BACKLOG.md:161` — *"121 processors: 34 delivered / **16 partial / 69 planned**"* (three wrong
  numbers in one clause)

A third, `docs/okf/frontend/features/schema-mapping-authoring.md:366`, says *"the 121-processor
catalog"* inside a **historical narrative** about what evidence existed when a mockup was drawn. That one
is arguably correct-as-history and should not be mechanically bumped.

---

### Row 5.4 — delivered/partial/planned split — **UNDISPUTED-DOC** (the catalog status is right)

Truth 34/18/67 (§0.2). Frontmatter `:4` says 33/17/69; body `:23`–`:24` says 34/16/69. Both sum to 119,
which is how they survived — the totals were checked, the split was not.

**The s3/gcs question, decided: the DOC is wrong, not the catalog.**

- `ProcessorCatalog.java` s3: `Status.PARTIAL`, `nodeType = "acquisition"`, note *"Connection kind exists
  (s3 connector, SDK-free SigV4; covers MinIO / GCS-interop); no proven end-to-end acquisition-node
  run"*.
- gcs: `Status.PARTIAL`, `nodeType = "acquisition"`, note *"Connection kind exists (gcs connector,
  native JSON API + service-account OAuth2); no proven end-to-end acquisition-node run"*.
- The connectors exist: `inspecto-connectors/src/main/java/com/gamma/acquire/connectors/S3Connector.java`,
  `S3ConnectorFactory.java`, `AwsSigV4.java`, `GcsConnector.java`, `GcsConnectorFactory.java`,
  `GcpServiceAccountToken.java`, over `AbstractHttpObjectStoreConnector.java`.
- Those notes are **the same shape** as `azure` (*"Connection kind exists (azure blob connector); ADLS
  Gen2 semantics not proven"*) and `kafka` (*"…consumer-group ingest as a Collector not proven"*) — both
  of which `step-catalog.md` already files under **Partial**. The catalog is internally consistent: all
  four are connectors that ship without a proven acquisition-node run.
- **PLANNED is not even representable for them.** The static-block invariant
  `ProcessorCatalog.java:188`–`:189` throws `"… is PLANNED but maps onto something — fix the status or
  the mapping"` for a PLANNED entry with a non-null `nodeType`. Flipping s3/gcs to PLANNED would fail
  class initialisation unless their `nodeType` were also deleted — which would delete the true fact that
  the connector ships.
- **The generated board already agrees with the catalog:** `docs/EDITIONS.md:177` (SP-ACQ-06, s3) and
  `:179` (SP-ACQ-08, gcs) render 🟡 / 🟡 / 🟡 with the full notes — identical treatment to azure
  (`:178`) and kafka (`:180`).

⇒ **No code change, no `render-processor-board.mjs` regeneration, no `ProcessorCatalogContractTest`
churn.** The defect is confined to the hand-maintained ACQ block of `step-catalog.md`: heading `:345`
says `4 delivered · 3 partial · 13 planned` (truth 4/5/11), the Partial table `:356`–`:362` is missing
s3 and gcs, and the Planned table `:364`–`:381` wrongly contains them with an empty `—` Note where the
catalog has real text.

`docs/BACKLOG.md:161` carries the same wrong split (34/16/69) — adjacent site, not named in the row.

---

### Row 5.5 — step kinds 15 vs 16 — **UNDISPUTED-DOC** (16 is right)

Truth 16 (§0.3). `step-catalog.md` says 15 at `:4` (frontmatter `description`) and `:15` (body).

🔴 **The page's own Part A already documents all 16** — ten `##` headings at `:59`, `:85` (which covers
the seven `parser.*` frontends), `:120`, `:145`, `:169`, `:192`, `:213`, `:244`, `:265`, `:300` ⇒
1 + 7 + 8 = 16. `lookup` has a full section at `:145`. So the number is stale prose contradicted by the
body beneath it; the plan's diagnosis ("not bumped when `lookup` landed") is right.

⚠ **Adjacent same-defect site, not named in the row:**
`docs/okf/backend/pipeline-graph/index.md:31` — *"all 15 delivered Step kinds"*. The two pages must
agree, so it needs the same bump.

---

### Row 5.6 — stale paths and dead components in `catalog-vs-executors.md` — **UNDISPUTED-DOC**

Three separate defects, all confirmed:

1. **`app-pipeline-load-definition` no longer exists.** `catalog-vs-executors.md:188`–`:190` cites
   *"bespoke rule-grid component `app-pipeline-load-definition`
   (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-load-definition.component.ts`)"*. Zero matches
   for either the selector or the filename anywhere under `inspecto-ui/src`; that directory now holds
   only `grammar-editor.dialog.*` and `measure-grammar.*`. (By contrast
   `app-pipeline-config-definition` at `:211` **does** exist —
   `inspecto-ui/src/app/modules/admin/pipelines/pipeline-config-definition.component.ts` — leave it.)

2. **`transform.map` / `TransformCompiler` treated as live paths.** Confirmed stale at `:230`–`:231`
   (*"on the legacy `transform.map` path `TransformCompiler` splices an `EXPR` rule's text verbatim"*)
   and `:237`–`:238` (*"adding a new `transformType` to `TransformCompiler`'s SQL-fragment registry"* —
   that registry is gone, `TransformCompiler.java:15-18`) and `:248` (*"several catalog ids fan into the
   same `transform.map` or `transform.sql` branch"*). Also the opening paragraph `:13`–`:16` still
   apportions the five entries across *"`transform.map` (2), `transform.sql` (2) … `transform.join`
   (1)"*.
   🔴 **But `:185` is already right** — *"`TransformCompiler` now compiles PARTITION and event-time
   columns only"* — which matches `TransformCompiler.java:11-13` exactly. The page is internally split,
   as with `transform.lookup`.

3. **Every `RowShaper.java:NNN` is stale** — see §1 for the full mapping, with the one exception
   (`:97`) noted there.

---

### Row 5.7 — the emit table omits `transform.lookup` — **UNDISPUTED-DOC**

`node-types.md:115`–`:130` lists 14 rows: `transform.map` (struck), `filter`, `select`, `derive`, `sql`,
`validate`, `dedup`, `dedup.marker`, `route`, `join`, `summarize`, `split`, `merge`, `enrichment`. No
`transform.lookup`.

Ground truth for the missing row — `BuiltinNodeType.java:109`–`:111`: accepts `Set.of(PipelineRel.DATA)`,
emits `Set.of(PipelineRel.DATA)` (one relation), `emitsNamedRoutes = false` (that trailing boolean is
`emitsNamedRoutes`, per the constructor at `BuiltinNodeType.java:201`–`:209` and the accessor at `:218`).
Authorable: yes — `PipelineEditable.java:142` (`LOWERABLE`) + recipe verb `lookup`.

The plan's framing is right that this is the table whose job is to prevent an unsafe fold: a lookup emits
**`DATA` only**, so it *is* projection-shaped and would look foldable — the safeguard is
`step-catalog.md:155`–`:156` (*"Emits `data` only — a lookup changes values, never row counts"*), which
is exactly the fact the emit table is supposed to carry.

---

## 3. Bucket summary

| Row | Bucket | Right side |
|---|---|---|
| 4.4 | DISPUTED | evidence true, conclusion refuted by the generators |
| 4.5 | UNDISPUTED-DOC | the ratchet / the "16 / 6" reading |
| 4.6 | UNDISPUTED-DOC | both docs half-right; `toon-config.md` owes the trap, `configuration.md` owes the mechanism |
| 5.1 | UNDISPUTED-DOC | the code + `step-catalog.md` |
| 5.2 | UNDISPUTED-DOC | the code (`RECIPE_VERBS`) |
| 5.3 | UNDISPUTED-DOC | `step-catalog.md` (119) |
| 5.4 | UNDISPUTED-DOC | the CATALOG status (34/18/67); the doc's Planned table is wrong |
| 5.5 | UNDISPUTED-DOC | the contract (16) |
| 5.6 | UNDISPUTED-DOC | the code |
| 5.7 | UNDISPUTED-DOC | the code |

**No `UNDISPUTED-CODE` rows.** Row 5.4 was the candidate and it resolved the other way: the catalog
status is correct and the generated `EDITIONS.md` board already reflects it.

---

## 4. Edit set

All OLD strings verified unique with `grep -cF` (count = 1 each). Files, in order:
`pipeline-config-keys.md` (5 edits) · `toon-config.md` (1) · `configuration.md` (1, +2 settled from the
DISPUTED row 4.4) · `catalog-vs-executors.md` (8) · `node-types.md` (2) · `step-catalog.md` (6) ·
`pipeline-graph/index.md` (1, adjacent) · `pipeline-editor.md` + `BACKLOG.md` (adjacent, flagged).

See the chat reply for the verbatim FILE/OLD/NEW blocks; they are reproduced from the byte-exact
extractions taken during this pass.

### Adjacent same-defect sites found while adjudicating (not named in any row)

| Site | Defect | Same class as |
|---|---|---|
| `docs/okf/backend/pipeline-graph/index.md:31` | "all 15 delivered Step kinds" | 5.5 |
| `docs/okf/frontend/features/pipeline-editor.md:121` | "121 processors" | 5.3 |
| `docs/BACKLOG.md:161` | "121 processors: 34 delivered / 16 partial / 69 planned" | 5.3 + 5.4 |
| `docs/okf/backend/engine/node-types.md:140` | `RowShaper.java:494-512` (real `:554`) | 5.6 |
| `inspecto-engine/.../PipelineProjection.java:71-72` | stale javadoc: "`map` authors a `transform.map` node in the GRAPH editor" | 5.2 — a CODE comment, one file from the correct list |
| `docs/okf/frontend/features/schema-mapping-authoring.md:366` | "the 121-processor catalog" | 5.3, but **historical narrative** — do not mechanically bump |
