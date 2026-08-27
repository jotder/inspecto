# Maven reactor & modularization (as-built)

How the reactor is shaped, why, and the rules for extracting further modules. Distilled from
`modularization-optimization-plan.md` (completed 2026-07-21, archived in
`../../../archived-documents/plans-archive/`) — that plan's findings sections hold the full evidence
base and the per-item history.

## Reactor shape (2026-07-22, +WS-D increments 2–4: fp-etl, fp-event, fp-acquire)

Build order (root `pom.xml`, parent `inspecto-parent`):

| # | Directory | artifactId | Role |
|---|---|---|---|
| 1 | `inspecto-api/` | `inspecto-api` | **Leaf, dependency-free**: only `com.gamma.api.PublicApi`, the stability-contract annotation. Behavior code never lives here. |
| 2 | `inspecto-util/` | `inspecto-util` | **Leaf w.r.t. `com.gamma`** (imports nothing from other core packages): `com.gamma.util` — the DuckDB access point (`DuckDbUtil` + JDBC/summarize/schema helpers), CSV/TOON I/O, file movers/walkers, tar/gzip, bounded history, `DottedPath`. Deps: duckdb_jdbc + opencsv + univocity + commons-compress + commons-lang3 + gson + jtoon + jackson. (The `ura` CLI `MainApp` — the one class in the old `com.gamma.util` that reached into core — was relocated to `com.gamma.inspector.MainApp` so this stays a clean leaf; see playbook rule 6.) |
| 3 | `inspecto-config/` | `inspecto-config` | `com.gamma.config` — spec / io (TOON codec) / safety. Deps: fp-api + jtoon + jackson. |
| 4 | `inspecto-sql/` | `inspecto-sql` | **Foundational leaf**: `com.gamma.sql` — the read-only DuckDB SQL sandbox (`SqlSandbox`/`SqlSandboxPolicy`), `SqlOracle`, `SqlGuard`, `SqlViews`. Deps: fp-api + fp-config + fp-util only (no external deps; DuckDB via `util.DuckDbUtil`). |
| 5 | `inspecto-etl/` | `inspecto-etl` | **Foundation leaf (WS-D increment 2, 2026-07-22)**: `com.gamma.etl` — `PipelineConfig`, the CSV/fixed-width ingesters, batch planning/commit/manifest, quarantine, lineage, partitioned Parquet output. Deps: fp-api + fp-util + univocity, duckdb, jtoon, jackson, slf4j, commons-compress, gson (no logback — etl doesn't own an appender). Publishes a **test-jar** (`com.gamma.etl.TestConfigs` + `PipelineConfigBatchTest`, the shared `PipelineConfig` builders). |
| 6 | `inspecto-event/` | `inspecto-event` | **Leaf-pair (WS-D increment 3, 2026-07-22)**: `com.gamma.event` + `com.gamma.metrics` — the Operational-Intelligence event store (`EventLog`/`EventStore`/`EventStoreAppender`, `ParquetEventStore`, saved views, secret scrubbing) and the metric registry it depends on; mutually cyclic with each other, not with anything else. Deps: fp-api/util/sql/**etl** + duckdb, jackson, slf4j, **logback-classic**. Owns `logback.xml` + `EventStoreAppender`. |
| 7 | `inspecto-acquire/` | `inspecto-acquire` | **Leaf (WS-D increment 4, 2026-07-22)**: `com.gamma.acquire` — connectors, connection profiles/registry/workbench, the fingerprint ledger, stability gate, gap detection, retry/circuit-breaker/rate-limit policies. No longer SCC-trapped once `etl` became a foundation leaf. Deps: fp-api/config/util/**etl**/**event**. |
| 8 | `inspecto-engine/` | `inspecto-engine` | **The remaining engine cluster**: `signal`, `query`, `pipeline`, `inspector`, `ingester`, `ops`, `job`, `enrich`, `alert`, `notify`, `catalog`. Deps: fp-api/util/config/sql/**etl**(+test-jar)/**event**/**acquire** + duckdb, jtoon, jackson, slf4j. Owns both `META-INF/services` files (`catalog.spi.DescriptionProvider`, `notify.NotificationChannel`). No longer owns logback-classic or a test-jar publish (both moved with event/etl). |
| 9 | `inspecto/` | `inspecto-processor` | The core / composition root: `service`, `control`, `report`, `assist`, `exchange`, `expectation`, `intelligence`, `model`; ships the shaded fat JAR. Depends DOWN on fp-api/util/config/sql/**etl**(+test-jar)/**event**/**acquire**/**engine**. |
| 10–13 | `inspecto-agent/`, `-agent-hosted/`, `-connectors/`, `-intelligence/` | `inspecto-*` | Siblings; each depends on core (and resolves the leaf/etl/event/acquire/engine modules transitively). |
| (opt) | `inspecto-security/` | `inspecto-security` | Standard-edition only, behind `-Pedition-standard` — not in the default `<modules>`. |

Binding constraints (unchanged by the split): framework-free (JDK HttpServer, manual DI,
ServiceLoader SPI); **one deployable** — modularization is reactor-internal, the fat
`inspecto.jar` is unchanged; editions are build flavors, never branches.

## Shared helper homes in `inspecto-util` (as-built 2026-08-27)

Phase-1 consolidation put ~60 duplicated `private static` copies into two homes. Reuse these; a new
per-file copy is a regression:

- **`com.gamma.util.Values`** — value coercion with the semantics encoded in the NAME, because the
  historical copies differed subtly: `str` (null→null, no trim) · `strOrEmpty` · `trimOrEmpty` ·
  `trimToNull` · `blankToNull` (no trim) · `intOr` · `putIfPresent` · `fileSafe`; plus typed map
  access `mapAt` (instanceof-guarded → null) vs **`castMapAt`** (bare-cast semantics: absent → null,
  non-map → `ClassCastException`) · `listAt` · `listOfMapsAt(m,key,where)`. ⚠ **Pick the method that
  matches the site, never "the closest one"** — `ValuesTest` pins every edge.
- **`com.gamma.util.SqlBuilder.quoteIdent`** — the DuckDB double-quote identifier escaper (four
  private copies removed). ⛔ **Not** for CSV: `MappingCsv.quote` (RFC4180 cell quoting) and
  `DbExportConnector.quote` (CSV header/cell quoting in `writeCsv`) are a DIFFERENT family despite
  byte-identical bodies — the call site decides. ⛔ Also not for single-quote STRING literals
  (`ExpectationEvaluator.literal`, `SqlViews`' pinned `'`→`''`).

Deliberately left as local copies, each verified different: `PipelineJobRunner.safe` (no null-guard,
where `fileSafe(null)` yields `"_"`), `EventObjectBridge.putIfPresent` (`Map<String,String>` AND
excludes blanks), `RowShaper.str` (a `PipelineNode` accessor, not a map lookup).

## Version management (M1)

Drift-prone shared external versions live ONCE in the parent `<dependencyManagement>`
(`junit`, `langchain4j`, `eoiagent`, `postgresql`, since S5 `jtoon` + `jackson-databind`, and
since WS-D `duckdb_jdbc` + `univocity-parsers` + `commons-compress` + `gson`); modules declare those
artifacts version-free. `opencsv` stayed pinned in fp-util (single-owner after the split — no drift to
prevent). `logback-classic` is single-owner (fp-engine, pinned 1.5.18). Reactor-internal deps
(`inspecto-api`, `inspecto-util`, `inspecto-config`, `inspecto-sql`,
`inspecto-engine` — the last also managed as a `test-jar` entry) are parent-managed at
`${project.version}`. Single-owner deps stay pinned in their one module. JaCoCo's `coverage` profile lives in the parent (`mvn -Pcoverage test`
instruments every module).

## Build entry points — core is NOT standalone anymore

Since S5 the core depends on reactor siblings, so **a core-alone `mvn` from `inspecto/` only
resolves after a root `mvn install`**. Every entry point builds via the root reactor:

- CI (`.github/workflows/ci.yml`): root `mvn install` / `mvn test` — unaffected.
- `inspecto/package.ps1` step 1: `mvn clean package -pl inspecto -am` **from the repo root**
  (`-am` builds fp-api/fp-config in-pass; the shaded JAR still lands in `inspecto/target/`).
  Same idiom as its Standard-edition step 1c.
- Shade has no include-list → new reactor modules land in the fat JAR automatically.

## Module-extraction playbook (what S5 proved)

1. **Module-level acyclicity ≠ package-import acyclicity.** A package with clean imports can still
   be un-extractable: `config` imported only `PublicApi`, but `PublicApi` lived in core while core
   imported config → module cycle. Always check what the candidate imports *transitively lives in
   core* before declaring it "low-risk". Extract the blocking leaf first (hence fp-api before
   fp-config).
2. **Keep the Java package name unchanged** when moving — core's 32 config-importing files needed
   zero edits; the diff stays pure-rename and history survives (`git mv`).
3. **Surefire's working directory is the module root.** A test that walks a repo/module-relative
   fixture tree cannot move with its class: the shipped-examples round-trip test stayed in core
   (`ShippedExamplesRoundTripTest`) with the `examples/` tree it validates when the rest of
   `ConfigCodecTest` moved to fp-config.
4. **Gate every move on the FULL reactor** (`mvn -o clean test` from root), never a single module —
   that's also the only way to prove a removed sibling dep (S9) really is unused.
5. **Import-anchored grep is NOT enough to prove a package is a leaf.** A class can look dependency-free
   by its `import` lines yet reach into core via a **fully-qualified inline** call
   (`com.gamma.inspector.ReprocessCommand.run(...)` in `MainApp`) or a fully-qualified `{@link}`/`{@code}`
   in javadoc — neither has an `import` line to grep. WS-D's initial `grep 'import com.gamma…'` scan
   cleared `util` as a pure leaf and MISSED both `MainApp`'s core call (a real compile break, caught only
   by the reactor build) and a `com.google.gson.Gson` inline ref (a missing pom dep). Scan with
   `grep -rhoE 'com\.gamma\.[a-z]+'` (matches import AND inline) for internal edges, and
   `grep -rhoE '\b(com|org|dev|…)\.[a-z]+\.[a-z]+'` for external deps before writing the module pom.
6. **One non-leaf class in an otherwise-leaf package → move it to its natural home in core.** `MainApp`
   (the `ura` CLI) was the sole `com.gamma.util` class that dispatches into core (it calls
   `com.gamma.inspector.ReprocessCommand`). It was relocated to `com.gamma.inspector.MainApp` — beside
   `CollectorProcessor` (the fat-jar Main-Class) and `ReprocessCommand`, the package that already holds the
   app/CLI entry points — so `com.gamma.util` moves out whole and fp-util is a genuine leaf with **no split
   package**. Cost: the launcher scripts (`ura.sh`/`ura.bat`/`package.ps1`) + a handful of doc mentions of
   the raw FQN, all mechanical; the eight util classes `MainApp` constructs were already `public`, so no
   visibility widening. (A split package — keeping `MainApp` in core under `com.gamma.util` — is legal on
   the plain classpath and was the zero-churn alternative, but leaves a `util`-named class that isn't a
   utility and blurs the module boundary the split exists to sharpen.)
7. **Over-counting is as wrong as under-counting (the inverse of rule 5).** An import/inline-FQN scan
   that does **not** strip comment/javadoc lines FALSELY reports edges: the old `etl↔service` "blocker"
   was pure `{@link}`/`{@code}` in javadoc, which `javac` ignores. Confirm a suspected edge is real code
   (an `import`, or an FQN outside `//`, `/* */`, `{@link}`, `{@code}`) before treating it as a blocker.
   The corrected scan strips comment lines: `grep -vE '^\s*\*' | grep -vE '\{@(link|code)' | grep -vE '//'`.
8. **Import-clean ≠ build-clean — two things imports never reveal.** (a) **Shared test fixtures.** A test
   helper class in a moved package that is `import`ed by tests that stay behind needs a **test-jar**
   (maven-jar-plugin `test-jar` goal on the producer; `<type>test-jar><scope>test</scope>` on the
   consumer) — Maven does not share test classes across modules. Find these by scanning stay-behind test
   dirs for imports of the moved packages. (b) **Resource-wired classes.** A resource that names a moved
   class by FQN (e.g. `logback.xml`'s `EventStoreAppender` appender) must travel WITH that class, or the
   moved module's own tests that rely on the resource break. Always inventory `META-INF/services/*` and
   `*.xml`/`*.properties` resources for FQNs of moved classes before the move.

## The engine-cluster split (WS-D, shipped 2026-07-22)

The 15-package strongly-connected engine — `etl, event, signal, query, pipeline, inspector, acquire,
ingester, ops, job, enrich, alert, metrics, notify, catalog` — was extracted whole, in ONE move, into
`fp-engine` below core. This corrected the earlier (now-deleted) analysis that had `etl`/`event` gated
on the **M2 `CollectorService` decomposition**: that conclusion counted javadoc `{@link}` references as
compile edges (playbook rule 7). Ground truth confirmed empirically: the SCC was tied to the composition
root by exactly **two `job` edges** — `job→service.Scheduler` (cut by relocating `Scheduler`→`util`) and
`job→report.ReportService` (cut by the `ReportRunner` SPI — `report` now depends **down** on `job`). M2
is maintainability-only, **not** a split blocker.

**§1.7 cycle-breaking prep that made the SCC coherent (all DONE, shipped before the split):**
- ✅ `CronExpression` + `Scheduler` `service` → `util` (the two edges into `service` from scheduling).
- ✅ `StatusStore` (interface) `service` → `etl` — broke the `service ↔ catalog` cycle (impls stay in `service`).
- ✅ `BatchEventBus` `service` → `etl` (beside its `BatchEvent` payload).
- ✅ `ReportRunner` SPI inverts `job→report` — `ReportService implements ReportRunner` (covariant `Object` returns).

**As-built facts (verified by the full reactor build, not just import scans):**
- **Import-clean = build-clean, but only after resolving two things import scans don't show.**
  (1) `com.gamma.etl.TestConfigs` is a test fixture used by ~45 core `control`/`report`/`service` tests;
  Maven does not share test classes across modules, so fp-engine publishes a **test-jar** (maven-jar-plugin
  `test-jar` goal) that core consumes in test scope. (2) `logback.xml` wires the engine-owned
  `event.EventStoreAppender`, so it moved to **engine** resources (co-located with the appender + on
  engine's own test classpath for `EventLogAndAppenderTest`).
- **Dependency repartition:** univocity/duckdb/commons-compress/gson/logback-classic are cluster-only —
  they moved to fp-engine and reach the fat JAR **transitively** through core→fp-engine. Core kept only
  the third-party it uses directly (jackson ×4, slf4j ×23, jtoon ×6) + the leaf modules.
- **Fat JAR unchanged:** shade (in core) has no include-list, so fp-engine's classes/resources bundle
  automatically. Verified in `inspecto-processor-4.0.0-SNAPSHOT.jar`: `Main-Class: com.gamma.inspector.CollectorProcessor`
  present, `logback.xml` at root, both service files present, engine + third-party classes bundled.

## Intra-engine structure (measured 2026-07-22) — why the sub-splits are NOT mechanical

`fp-engine` is one coarse module. A follow-up analysis (inline-aware, comment-stripped import + FQN
scan, both directions) mapped its internal shape. **The optimistic "trivially available" / "falls out
naturally" claims for the sub-splits were WRONG — the third time this arc under-estimated coupling.**

Layering **as first measured** (top = consumed only by core; bottom = the mutually-cyclic core) —
increment 1 below then reshaped the bottom row:

| Layer | Packages | Note |
|---|---|---|
| Top (in-degree 0 within engine) | `inspector`, `ingester`, `notify`, `alert` | `inspector` holds the fat-jar Main-Class |
| Mid | `catalog` | imported only by `alert` |
| **SCC (10 pkgs, mutually cyclic)** | `etl, event, metrics, pipeline, job, acquire, signal, query, enrich, ops` | inseparable without cycle-breaking |

- **`fp-acquire` below engine was NOT available as first measured.** `acquire` was *inside* the SCC
  (`acquire→etl→pipeline→job→acquire`), so it could not drop below the rest of the engine until the SCC
  was decomposed. (The S5 ③ "falls out naturally" premise is retired.) **Increment 1 changed this** —
  `acquire` fell out of the SCC (see below).
- **The §2.3 three-cluster sub-split (fp-core-etl / fp-ops / fp-catalog) was impossible as specified**,
  because those clusters split packages (`etl`, `event`, `pipeline`, `ops`, `query`) that all lived in
  the *one* 10-package SCC. Increment 1 shrank that SCC but did not (and was not meant to) realize the
  §2.3 clusters exactly; further work is still deliberate cycle-breaking, not a mechanical move.
- **What the map showed as feasible — and increment 1 (2026-07-22) then DID.** The SCC was held
  together substantially by `etl` importing *up* into `event`/`pipeline`/`query`/`signal` via only
  **two files**: `etl.DecisionRuleApplier` (`pipeline.DecisionRules` + `query.ConditionSql`) and
  `etl.BatchAuditWriter` (`event.EventLog` + `signal.Signal` for the `pipeline.batch.*` observability
  tail). Both were cut without touching behavior:
    - `DecisionRuleApplier` → relocated to `com.gamma.pipeline` (its cohesive home with `DecisionRules`);
      all 3 callers (`inspector`/`enrich`/`job`) are higher-layer, so no etl→pipeline edge returns.
    - `BatchAuditWriter` → the inlined Signal build+emit moved to the new `com.gamma.signal.PipelineBatchSignal`,
      wired via an injected `setTerminalBatchSink(Consumer<BatchEvent>)` that `CollectorProcessor` sets to
      `PipelineBatchSignal::emit`. `BatchEvent` already carried every field the Signal needs, so it is a
      pure fan-out split. (One test method moved etl→signal to keep etl-test clean of the up-packages.)
  **Result — the mega-SCC fragmented (verified by re-mapping, full reactor green, 1884 tests):**

  | Before (10-pkg SCC) | After increment 1 |
  |---|---|
  | `etl, event, metrics, pipeline, job, acquire, signal, query, enrich, ops` | `etl` = **foundation leaf** (out-degree 0 in engine) · SCC → **`{pipeline, job, query, enrich}`** + **`{event, metrics}`** · `acquire`, `signal`, `ops`, `catalog` dropped OUT (now simple downward deps on `etl`/`event`) |

  So `acquire` was no longer SCC-trapped (it imported only `etl`+`event`), and `etl` was cleanly
  extractable as an `fp-etl` module below the rest. Both `event`/`metrics` and `acquire` were
  subsequently extracted as their own modules (increments 3–4, below). Further fragmentation of the
  remaining `{pipeline,job,query,enrich}` SCC is the same class of deliberate, deadlock-sensitive
  design work — do it only if finer module granularity is wanted. The coarse `fp-engine` already
  delivers the acyclic core↔engine boundary, which was the whole point of WS-D. **Triaged 2026-07-22
  and deferred (nobody has requested finer granularity):** the actual `fp-query`/`fp-job`/`fp-enrich`
  module split is NOT a single clean increment — `query`/`job` also depend on `signal` and `ops`
  (outside the four-package group; their own test up-imports unscanned) which must be co-extracted, and
  `job`'s `SharedDottedPathGrammarTest` imports `com.gamma.notify.NotificationTemplate` (a rule-5 test
  up-import that must be cut, notify staying behind).
- **M2 `CollectorService`/`SourceService` decomposition — CLOSED (won't-do), triaged 2026-07-22.**
  `CollectorService` (1266 lines) already reads as a composition-root/facade wiring ~15 extracted
  collaborators and is covered by 6 focused test files; maintainability-only, NOT a split blocker.

**The `{pipeline, job, query, enrich}` SCC was itself decomposed (2026-07-22, same-day follow-up).**
An empirical edge scan (comment-stripped import + inline-FQN, both directions) found the whole
4-package cycle was held together by exactly **two back-edges out of `pipeline`**, with `job` and
`query` otherwise legitimately consuming `pipeline` one-way:
- `pipeline.exec.PipelineJobRunner implements job.Job` (an SPI-implementing class living in the
  wrong package) — relocated to `com.gamma.job` (package-only move; all its `pipeline`/`pipeline.exec`
  dependents are public, no split-package issue). Cuts `pipeline→job`.
- `pipeline.DecisionRuleApplier` importing `query.ConditionSql` for one predicate-compile call —
  relocated to `com.gamma.query` (its only real `pipeline` dependency, `DecisionRules.forTarget`,
  is public). Cuts `pipeline→query`.

Relocating `DecisionRuleApplier` had a bonus effect: it was `enrich`'s *only* import of `pipeline`
(`EnrichmentEngine`) — so `enrich` dropped out of the SCC entirely as a side effect, not a separate cut.

Result: `pipeline` is now a clean base (no more up-imports within this cluster); `query` and `enrich`
sit above it one-way; `job` sits above all three. Verified by the full reactor `mvn -o clean test`:
**1884 tests, 0 failures, 0 errors, 3 skipped** — exact match to baseline (relocations only, callers'
imports updated in `EnrichmentEngine`/`BatchIngestStrategy`/`SqlTemplateJob`/`JobService`/
`DecisionRoutes`/`DecisionRuleWiringTest`, javadoc `@link`s fixed in `ConservationCheck`/`ViewQuery`/
`PartitionSinkWriter`/`ViewStore`). This is package-level layering only (all four packages are still
one `fp-engine` module) — a prerequisite for ever extracting `fp-query`/`fp-job`/`fp-enrich` as
separate modules below `fp-engine`, not an extraction itself.

## ⚠ Re-measured 2026-08-27 — the layering above is HISTORY, not current state

Everything above this line accurately records what the 2026-07-22 work achieved. **It no longer
describes the code.** A repo-wide census (comment-stripped imports + inline-FQN scan, playbook rules
5 and 7 applied) found **three strongly-connected components the sections above do not mention**,
all created by packages and edges added after that measurement. Read this section, not the
"`pipeline` is now a clean base" line, when deciding anything about extraction.

**Still true as documented:** `event ↔ metrics` — a deliberate mutually-cyclic leaf pair, unchanged.

| SCC | Packages | Real edges holding it | Verdict |
|---|---|---|---|
| **C1** | `{ops, ops.workflow, ops.tag, ops.link, ops.note, ops.findings, pipeline}` | exactly **two**: `ops/AnnotationKinds.java:3` → `pipeline.ComponentStore` (reads `WRITABLE_TYPES` to build the annotation-target vocabulary) and `pipeline/NodeAttribute.java:8` → `ops.findings.FindingsSpec` (delegates the `TYPES`/`TIERS` published enums so they cannot drift) | relocation — but see the caveat below |
| **C2** | `{catalog.spi, catalog, alert, pipeline.exec, job, consignment, enrich}` | **one file**: `consignment/ConsignmentProcessJobType.java:4-11` `implements job.JobTypeProvider`, plus its sibling `consignment/ProcessorContext.java:4` importing `job.RunLog` for three delegate methods | relocation — exact precedent match |
| **C3** (core, `inspecto`) | `{report, intelligence.spi, assist.spi, service}` | `report`/`assist.spi`/`intelligence.spi` all import the concrete `service.CollectorService`, which imports back into all three | ⛔ **NOT cuttable by role interfaces** — corrected 2026-08-27, see below. Still 4 packages after `ReadModel` shipped. |

**`consignment` postdates this document entirely** — it appears nowhere above and is C2's tangle point.

**What is NOT a cycle, and must not be "fixed":**
- `catalog ↔ catalog.spi` is the **deliberate SPI-pair pattern**, the same accepted shape as
  `event ↔ metrics`. Leave it.
- `job → consignment` is wide (7 files) and **legitimately one-way** — `consignment` belongs below
  `job`. Only the reverse edge is the problem.
- `alert → catalog`, `catalog → enrich`, `enrich → consignment`, `pipeline.exec → consignment` are
  all clean one-way edges. The only reverse reference found in `consignment`
  (`DbConsignmentOutputStore.java:22`) is a `{@link}` javadoc — **not a compile edge** (playbook
  rule 7 caught it, as it caught the phantom `etl↔service` blocker before). A `//` comment at
  `pipeline/ComponentStore.java:84` mentioning `FindingsSpec` is likewise not an edge.

### 🔴 All three cycles are held by `@PublicApi` classes — cutting any is a MAJOR-VERSION decision

This is the finding that governs Phase C, and it was missed by the cohesion/import analysis that
produced the table above. Every class whose relocation would cut a cycle is on the published surface:

| Class | Marker | Role in the cycle |
|---|---|---|
| `consignment/ConsignmentProcessJobType` | `@PublicApi(5.0.0)` | the whole of C2's reverse edge |
| `consignment/ProcessorContext` | `@PublicApi(5.0.0)` | C2's sibling edge (`job.RunLog` delegation) |
| `ops/AnnotationKinds` | `@PublicApi(4.9.0)` | C1 edge 1 → `pipeline.ComponentStore` |
| `ops/findings/FindingsSpec` | `@PublicApi(4.6.0)` | C1 edge 2's target (stays put either way) |
| `pipeline/ComponentStore` | `@PublicApi(4.3.0)` | C1 edge 1's target (stays put either way) |
| `pipeline/NodeAttribute` | **unmarked** | C1 edge 2's source — the only free class in the set |

Per [`../control-plane/api-stability.md`](../control-plane/api-stability.md), a `@PublicApi` type may
change incompatibly **only on a major version bump, noted in release notes**. Relocating one changes
its FQN, which breaks every plugin author and embedder importing it. So:

- **C2 is BLOCKED.** Its only cut is relocating two `@PublicApi(5.0.0)` classes. A deprecated alias
  left behind at the old FQN does not help — the alias would still live in `consignment` and still
  reference `job`, so the cycle survives the workaround.
- **C1 is BLOCKED as a relocation** (`AnnotationKinds` is published). It *could* be cut without an
  API break by **inverting the constant ownership** on edge 2 — move the `TYPES`/`TIERS` canonical
  values to `pipeline` and have `FindingsSpec` delegate, so both edges point `ops → pipeline` and the
  cycle opens. That is a design change, not a relocation, so under the operator's stated default it
  is recorded here rather than built unasked.
- **C3** is the same shape (see the Phase 2 plan), but ⚠ **"partly cuttable" was measured wrong — see
  the correction below. It is NOT cuttable by role interfaces.**

#### 🔴 C3's `report` edge is held THREE ways, and a role interface removes only one (2026-08-27)

`ReadModel` (`com.gamma.service`) shipped, and all six `CollectorService` collaborators were narrowed
to it. **The `report → service` edge did not move.** `ReportService`'s import block after the
conversion still reads `EnrichmentService`, `CollectorService`, `ReadModel`:

| # | Holder | Why a `ReadModel` conversion does not remove it |
|---|---|---|
| 1 | the `CollectorService` field | removed — this is the only one a role interface touches |
| 2 | `EnrichmentService` | an unrelated `com.gamma.service` type `ReportService` also uses |
| 3 | ~~`CollectorService.PipelineView`~~ | ✅ **REMOVED 4.0.0** — promoted to a top-level `com.gamma.service.PipelineView` under operator decision #4. ⚠ **This dropped the HOLDER, not the EDGE:** the record still lives in `com.gamma.service`, so `ReportService` now imports `PipelineView` instead of `CollectorService` — same edge, different holder |

⭐ **Holder 3 generalises well beyond this cycle: a nested public record makes its enclosing class an
unavoidable import for every caller of any method that returns it.** (Confirmed by removing it: promoting
the three records dropped `CollectorService`'s fan-in from **25 files to 16**.) No fan-in/fan-out metric surfaces
this — the dependency is in the *return type's spelling*, not in the call graph. Check for nested
types before predicting that an interface will cut an edge.

**Status after 4.0.0:** the `ReadModel` conversion and the record promotion are both **done**, and the
`report -> service` edge **still stands** — `ReportService` imports `EnrichmentService` and
`PipelineView`, both `com.gamma.service`. ⭐ **The lesson: promoting a nested type removes the holder
but not the edge, because the promoted type lands in the same package.** Cutting the edge needs the
two things still outstanding: a role interface for `EnrichmentService`, and a home for the view
records OUTSIDE `com.gamma.service`. Both are redesigns; under the operator's stated default they are
recorded here rather than built unasked.

**What Phase A did cut:** exactly one edge — `intelligence.context → service`, because `ContextBroker`
needed only `events()` and so has zero remaining `CollectorService` references. C3 stays at four
packages.

⚠ **The grep trap that nearly caused a wrong call here: 31 of the engine's 135 `@PublicApi` classes
write the annotation fully-qualified** (`@com.gamma.api.PublicApi`), so a naive `grep "@PublicApi"`
**under-reports the published surface by ~23%** and wrongly clears classes for relocation.
`AnnotationKinds` was cleared exactly that way before the second check caught it. Always match
`@(com\.gamma\.api\.)?PublicApi`. Same family of error as playbook rule 5 (import-anchored greps miss
fully-qualified usage) — it applies to annotations too.

**Caveat on C1's second edge — the one judgment call in the whole census.** `AnnotationKinds` has an
obvious cohesive home beside `ComponentStore`. `NodeAttribute ↔ FindingsSpec` does not: `FindingsSpec`
is cohesively `ops` vocabulary, while `NodeAttribute` is published pipeline-node-type API served at
`GET /pipelines/node-types` — so neither class wants to move into the other's package, and cutting it
means inverting the constants' ownership rather than relocating a file. **Do not treat that edge as
mechanical.**

**Test-source edges mirror the same two C1 files** (`NodeAttributesContractTest.java:128-129`,
`ops/note/NoteCoreTest.java:98`, both inline FQNs) and add no new blockers — but note they exist, as a
test up-import blocks extraction exactly as a main-source one does.

## `fp-etl` module extraction (WS-D increment 2, shipped 2026-07-22)

Extracted `com.gamma.etl` (main + tests) out of `fp-engine` into its own leaf module below it, now
that increment 1 made `etl` a foundation leaf. Plan: `docs/archived-documents/plans-archive/fp-etl-extraction-plan.md`.

- **Etl's test sources were NOT actually clean of up-imports** — the import-line grep from increment 1
  (`grep '^import com.gamma.…'`) only checks explicit import statements. Three test methods reached up
  via **fully-qualified inline calls** that have no import line (playbook rule 5, same failure mode as
  the original `MainApp`/gson miss): `SourceConfigTest` (15 tests, a genuine acquire+etl+event+inspector
  integration suite wearing the `etl` package name — ledgers, connectors, `CollectorProcessor.run()`,
  gap detection), `CommitLogTest.realRunRecordsCommit` (one method, `com.gamma.inspector.CollectorProcessor.run`),
  and `PhaseFConfigTest.postActionResolvesArchiveDateTemplate` (one method, `com.gamma.acquire.PostAction`
  — tests `acquire`'s own class, not etl at all).
- **Resolution:** `SourceConfigTest` moved whole to `inspecto-engine/src/test/java/com/gamma/acquire/`
  (an explicit operator call — not split into etl-only/integration slices, so `fp-etl` ships with no
  direct integration coverage beyond the trimmed `ConfigFromMapTest`, which is fine: the moved test still
  runs, just from `fp-engine`). The two one-method leaks became new small files:
  `acquire.PostActionTest` and `inspector.CommitLogIntegrationTest`. `ConfigFromMapTest`'s
  enrich/job `fromMap` assertions (unrelated to etl, just co-located) moved into the existing
  `EnrichmentConfigTest`/`JobConfigTest`.
- **Dependency repartition:** univocity/commons-compress/gson (etl-only) moved to fp-etl; duckdb/jtoon/
  jackson/slf4j are shared with fp-engine (declared in both, parent-managed so no drift). logback-classic
  stayed engine-only (etl owns no appender).
- **Test-jar chain:** fp-etl publishes a test-jar for `TestConfigs`/`PipelineConfigBatchTest`; fp-engine
  consumes it (its own ~20 test files use those fixtures) and core depends on it directly too (core's
  `PipelineConfigBatchTest` usage). fp-engine's own `maven-jar-plugin` test-jar publish was found unused
  by any consumer and dropped in increment 3 (see below) — don't re-add it speculatively; add it back
  only when a real cross-module fixture need shows up.
- **Verified:** full reactor `mvn -o clean test` — **12 modules, 1884 tests, 0 failures, 0 errors, 3
  skipped** — exact match to the pre-extraction baseline (no tests lost or gained, only relocated).

## `fp-event` module extraction (WS-D increment 3, shipped 2026-07-22)

Extracted `com.gamma.event` + `com.gamma.metrics` (main + tests) out of `fp-engine` into one leaf
module below `fp-etl` — they're mutually cyclic with each other (`event`→`metrics` for gauge/counter
emission, `metrics`→`event` for its own audit trail) but nothing else in the engine imports back into
either, confirmed by a comment-stripped inline-FQN scan of both packages (not just an import-line
grep, learning increment 2's lesson upfront this time — no leaks found).

- `logback.xml` (root logging config, wires `EVENT_STORE` → `event.EventStoreAppender`) moved with the
  appender class into fp-event's resources, per playbook rule 8.
- `fp-engine`'s own `logback-classic` dependency and unused `maven-jar-plugin` test-jar publish were
  dropped in the same commit — the appender was the only thing in fp-engine needing logback-classic,
  and no consumer anywhere in the reactor declared a dependency on fp-engine's test-jar (checked before
  removing, per the "no speculative dependencies" rule — it had been added defensively in increment 2
  and never used).
- **Verified:** full reactor `mvn -o clean test` — **13 modules, 1884 tests, 0 failures, 0 errors, 3
  skipped** — exact match to baseline.

## `fp-acquire` module extraction (WS-D increment 4, shipped 2026-07-22)

Extracted `com.gamma.acquire` (main + tests) out of `fp-engine` into its own leaf module below
`fp-event`, now that increment 1 made `etl` a foundation leaf (which is what pulled `acquire` out of
the SCC in the first place — acquire imports only `api`/`config`/`etl`/`event`/`util`, confirmed clean
by the same inline-FQN scan, both main and test trees).

- **This was originally scoped as "just extract fp-acquire" and turned out to require fp-event first** —
  `acquire`'s only up-dependency is `event`, which was still mutually cyclic with `metrics` until
  increment 3 shipped. Recorded here as another instance of this arc's recurring lesson: the "next"
  item on a follow-on list is rarely as small as it looks until you trace its actual transitive deps.
- **`SourceConfigTest` moved a second time.** It had already been relocated into the `acquire` test
  package in increment 2 (because it's a genuine acquire+etl+event+inspector integration suite, not an
  etl unit test). With `acquire` itself now extracted, and `inspector` staying behind in `fp-engine`,
  the test moved again — this time into `inspecto-engine/src/test/java/com/gamma/inspector/` as
  `SourceConfigIntegrationTest`. Same test, same operator call (don't split it), second address — a
  concrete illustration of why an integration test that spans multiple packages should be named for
  what it tests, not for whichever package happens to house it at the time.
- **Verified:** full reactor `mvn -o clean test` — **14 modules, 1884 tests, 0 failures, 0 errors, 3
  skipped**; new `fp-acquire` module runs 101 tests standalone.

## Related seams (shipped, documented elsewhere)

- `@PublicApi` freeze of the SPI surface (M3/S8) — `../control-plane/api-stability.md`.
- intelligence↔agent decoupling via the core `ModelSettings` read-side bridge (S9) — agent stays
  the single writer of `assist-settings.properties`; core owns a parallel read-side value type.
- `ControlApi.dispatch` middleware chain (S6), `SpaceManager.closeWithDeadline` (S7) —
  `../control-plane/control-api.md`.
- `PipelineNodeType` is a **reserved** ServiceLoader extension point (C7): built-ins implement it,
  external providers = zero by design; see its javadoc.
