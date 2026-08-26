# Java design-simplification & reorganization plan

Goal (operator, 2026-08-26): simplify Java design, framework code and module organization for
maintainability; prefer functional style; move reusable functions to utils; reuse code; reduce
dependencies; keep it modular; use generics where applicable. **Exclude AI/model modules**
(`inspecto-intelligence`, `assist`/AiDraft surfaces).

## Standing constraints (do not re-litigate)

- Reactor shape is AS-BUILT and settled — see `docs/okf/backend/modules/reactor.md`. The
  fp-query/fp-job/fp-enrich finer module split was **deliberately deferred** (nobody asked for finer
  granularity); M2 `CollectorService` decomposition was **CLOSED won't-do** (it is a composition-root
  facade). This plan targets *intra-module design*, not the module map.
- Framework-free is binding: JDK HttpServer, manual DI, ServiceLoader SPI. "Simplify framework code"
  means consolidating our hand-rolled plumbing, never adding a framework.
- One deployable (fat `inspecto.jar`); editions are build flavors.
- Behavior-preserving only: every slice gates on the full reactor `mvn -o clean test` matching the
  baseline (currently 3630/0/0/5 enterprise) + UI suite where touched.

## Grounding (to be filled from exploration agents)

### Module & dependency inventory (grounded 2026-08-26)

- The reactor is a **layered DAG with no compile-time cycles**: `api` → `util`/`config`/`sql` →
  `etl` → `event`/`acquire` → `engine` (279 .java, the largest) → `inspecto` host (131 .java).
  Five optional modules (`connectors`, `agent`, `security`, `policy`, `intelligence`) depend one-way
  on the host and wire back via ServiceLoader at runtime.
- **AI/model cluster (EXCLUDED from this plan):** `inspecto-agent`, `inspecto-agent-hosted`,
  `inspecto-intelligence` — the three langchain4j/eoiagent consumers.
- Tiny modules (`api` 1 file, `policy` 1, `agent-hosted` 2, `security` 3, `sql` 5) are **deliberately
  thin** (stability root; edition capsules) — not cruft; leave the module map alone.
- `inspecto-deploy` is not a Maven module (runtime distribution dir) — out of scope.
- `asn-parser` is a self-contained separate reactor (own groupId) — out of scope.
- Dependency-reduction candidates are therefore *within-module third-party usage*, not module
  edges: DuckDB/jackson/toonformat spread is parent-managed already (reactor.md §Version management);
  the real waste to hunt is duplicated in-code plumbing, not pom entries.

### Complexity & duplication hotspots (grounded 2026-08-26)

- **God classes (line counts):** `etl/PipelineConfig` 1716 · `service/CollectorService` 1693
  (⚠ decomposition previously CLOSED won't-do — treat as facade, don't split) ·
  `control/PipelineRoutes` 1677 · `etl/PipelineConfigParser` 1616 · `job/JobService` 1372 ·
  `ops/ObjectService` 1358 · `pipeline/PipelineEditable` 1081 · `control/ConfigRoutes` 1042 ·
  `control/ControlApi` 1030 · `etl/DuckDbCsvIngester` 884 · `job/MaintenanceJob` 855.
- **Duplicated helper family** (same private static reimplemented per file): `str` (~30 files),
  `blankToNull` (10), `quote` (6 — SQL quoting, belongs beside `SqlBuilder`/`inspecto-sql`),
  `norm` (6), `putIfPresent` (5), `intOr`/`exists`/`safe` (4–5 each).
- **Untyped plumbing:** `Map<String,Object>` ≈1900 sites (host 865, engine 786, etl 184);
  **121 files** carry `@SuppressWarnings("unchecked")` — concentrated in the same route/service/
  config classes.
- **inspecto-util under-adopted:** real utilities exist (`ToonHelper`, `SqlBuilder`, `Csv`,
  `AtomicFiles`, `TarUtil`, `DuckDbUtil`) but e.g. `ToonHelper` is referenced from only 32 external
  files while ~20 etl/engine files do their own TOON-adjacent handling.
- Internal structure of the god classes (switch-on-type-string chains, per-route try/catch
  boilerplate) is inferred from role+size, not yet quoted — each slice must ground before edit.

### Framework plumbing map (grounded 2026-08-26)

- **Much is already consolidated — do not rebuild it:** routes register via `RouteModule` (55
  modules wired in `ControlApi.java:398-410`, open/closed); the 4-gate write chain lives ONCE in
  `control/WriteGates.java` (28 files, 120 call sites); errors funnel through one `ApiException`
  → `ControlApi.dispatch` catch → `ApiContext.respondJson` choke point.
- **Residual route repetition:** oversized modules (`PipelineRoutes` 1677, `ConfigRoutes` 1042,
  `ObjectRoutes` 743, `BundleRoutes` 664, `ComponentRoutes` 564) still inline domain validation and
  error mapping; the lean idiom already exists (`NoteRoutes.java:37-88` — lambda handlers +
  `AnnotationTargets.mapErrors`/`gate`). Convergence target, not invention.
- **Manual DI:** the object graph is built inline in `CollectorService`'s constructor (~60 `new`s,
  5 overloaded constructors, order-sensitive wiring at `:403-560`). No separate composition root.
- **ServiceLoader:** 16 SPI points; loading style duplicated three ways with no shared helper —
  first-wins cached Optional (`AccessDeciders:20`, `Authenticators:21`, `TokenRelays:19`),
  collect-all (`Parsers:35`, `Decompressors:42/59`, `PipelineNodeTypes:31`,
  `MetadataGraphService:64`), inline `findFirst()` (`CollectorService:942,947`), plus an explicit
  classloader variant (`JobPackManager:195,201`).
- **Config plumbing overlap:** generic trio `ConfigCodec` (61) + `ConfigSpecs` (653) +
  `ConfigSafetyValidator` (465) AND pipeline-specific pair `PipelineCodec` (122) +
  `PipelineValidator` (325) — five classes, one encode→decode→validate shape, no common interface.

## Work slices

Ordered cheapest/safest first; each is independently shippable and gates on the full reactor
(`mvn -o clean test -Pedition-enterprise`, baseline **3630/0/0/5**) plus
`node tools/check-secrets.mjs` + `node tools/check-vocabulary.mjs` before every commit.
Every slice starts with its own grounding pass (the hotspot internals are inferred, not yet read).

### S1 — Shared helper consolidation → `inspecto-util` (mechanical, high ROI)

> **STATUS: core sweep SHIPPED `d5791116` (2026-08-26)** — `com.gamma.util.Values` + 38-file sweep,
> reactor 3651/0/0/5. Residuals deliberately left: (a) the `str(Map,key)` lookup-shaped helpers
> (~10 files — a different family; candidate for S4's `MapView` instead), (b) `RuleTemplate.str`
> (blankToNull semantics, no trim), (c) `ComponentPreview.intOr` (no Number branch — coercing a
> Double would change behavior), (d) sites in files that carried another shift's uncommitted
> changes (`PipelineCodec`, `PartitionSinkWriter`, `PipelineWatermarkStore`, `PipelineJobRunner`,
> `EventObjectBridge`) — sweep them once the tree is clean, (e) the `quote` family (own commit,
> semantics differ per site), (f) the control-plane `exists()` 5-copy family (control-local
> consolidation, fits S3's error-mapper work).

Create two small utility homes and sweep the duplicated private-static family into them:
- `com.gamma.util.Values` (or extend an existing helper if grounding finds one): `str`,
  `blankToNull`, `intOr`, `exists`, `safe`, `putIfPresent`, `norm` — generic where it pays
  (`<T> T or(Object, T fallback)`, `Optional`-returning accessors).
- SQL `quote` → beside `SqlBuilder` in `inspecto-util` (or `inspecto-sql` if the sites are all
  sql-adjacent — grounding decides; ⚠ two of the six sites double single-quotes deliberately,
  see BACKLOG's pinned `replace("'", "''")` — semantics must be preserved per site, NOT unified
  blindly. Sweep one helper family per commit; a helper with subtly different semantics at one
  site keeps its local copy with a comment.)
- ⚠ AI modules excluded: leave `inspecto-agent`'s copies alone.
- Verify each family: grep count of the local reimplementation goes to zero (excluding AI modules),
  reactor green.

### S2 — Generic `SpiLoader` (ServiceLoader unification; the generics case)

> **STATUS: grounded 2026-08-26 — the broad premise was REFUTED, the narrow one shipped.**
> Reading every site showed the "collect-all" loops (`Parsers`, `Decompressors`,
> `PipelineNodeTypes`, `MetadataGraphService`, `DeliveryStatusRoutes`, `ConsignmentProcessJobType`,
> `JobPackManager`) each carry *semantic* per-element logic — id validation, built-in override
> layering, best-suffix match, `configured()` filter, find-by-id, same-classloader filter. A generic
> `SpiLoader.all()` would have replaced ~3 lines in one file. NOT built; those sites stay as they are.
> What WAS triplicated verbatim (cache + first-wins scan + `forTest` seam) is the control-plane
> edition-seam trio — now one generic `com.gamma.control.SpiSlot<T>` with `Authenticators` /
> `AccessDeciders` / `TokenRelays` as thin typed facades (the facade names are the documented seam
> and stay). `SpiSlotTest` pins empty-caches / override / null-re-arms. `CollectorService`'s inline
> `findFirst()` sites were left: that file carries another shift's uncommitted changes, and it
> already has its own `OptionalAgentSlot<T>` abstraction with a different (register/init) lifecycle.

One small class in `inspecto-util`:
`SpiLoader.first(Class<T>): Optional<T>` (cached), `SpiLoader.all(Class<T>): List<T>`,
plus an explicit-classloader overload for the `JobPackManager` case. Port the ~10 duplicated
loading sites (`AccessDeciders`, `Authenticators`, `TokenRelays`, `Parsers`, `Decompressors`,
`PipelineNodeTypes`, `MetadataGraphService`, `CollectorService:942,947`, `JobPackManager`).
⚠ Preserve each site's caching/ordering semantics exactly; first-wins sites keep first-wins.
⚠ `inspecto-security`/`inspecto-policy` sites compile only under `-Pedition-standard`/
`-Pedition-enterprise` — the enterprise gate covers both.

### S3 — Route-module convergence (functional style where it shows most)

Converge oversized `*Routes` classes on the existing lean idiom (lambda handlers, shared
`mapErrors`-style domain-error mapping, `WriteGates` for the write chain):
1. Ground: per-class audit of `PipelineRoutes`, `ConfigRoutes`, `ObjectRoutes`, `BundleRoutes`,
   `ComponentRoutes` for inline try/catch + hand-rolled gate fragments.
2. Extract a shared domain-error mapper convention (generalize `AnnotationTargets.mapErrors` into
   `control` if grounding confirms it's route-generic) so modules stop inlining try/catch.
3. Split the two giants into cohesive `RouteModule`s (registration is open/closed, so splitting is
   additive): e.g. `PipelineRoutes` → pipeline CRUD / run-test / editor-support modules. Pure moves,
   endpoint behavior unchanged; the real-HTTP test classes must pass unmodified (they pin the
   contract). Follow the `endpoint` skill's gate order rules for anything touched.
   ⚠ `PipelineRoutes`/`ComponentRoutes` currently carry uncommitted changes from another shift —
   do not start S3 until the tree is clean.

### S4 — Typed access over `Map<String,Object>` plumbing (generics + records)

Not a big-bang DTO rewrite. Two moves:
1. A generic typed map view in `inspecto-util` (e.g. `MapView` with `str(key)`, `list(key, Class<T>)`,
   `map(key)`, `req(key, Class<T>)` throwing a uniform error) to replace ad-hoc casts — targets the
   121 `@SuppressWarnings("unchecked")` files, worst first (`ObjectService`, `PipelineEditable`,
   `ConfigRoutes`, `PipelineConfigParser`).
2. Small typed records at stable seams where a map is decoded once and passed around (grounding
   per seam; ⚠ many maps here are AUTHORED config — round-tripping through typed records risks the
   known lossy-projection failure class ([[recipe-projection-read-the-wrong-home]],
   [[unmodelled-config-stringified-on-save]]): any record introduced on a config path must carry
   the unmodelled-keys remainder or stay read-only).
Measure: `@SuppressWarnings("unchecked")` file count strictly decreases per commit; no config
round-trip changes byte-for-byte on the shipped examples (`ShippedExamplesRoundTripTest` guards this).

### S5 — Codec/validator seam (one shape, five classes)

> **STATUS: CLOSED as SKIP (grounded 2026-08-27), per this slice's own rule.** Measurement found
> the "five classes, one shape" premise wrong: `PipelineCodec` is a pure graph↔map mapper LAYERED ON
> `ConfigCodec` (map↔TOON) — composition, not duplication (`ConfigCodec.toToon(PipelineCodec.toMap(g))`
> in `PipelineStore:53,66,82` and PipelineRoutes). The validators check disjoint concerns (graph
> DAG/wiring vs path-jail/bounds/allowlists) with independent result types; the shared ERROR/WARNING
> vocabulary already lives in one `Severity` enum (`config/spec/Severity.java`). Genuinely duplicated
> code ≈ 0 lines; no call site would use a common interface polymorphically. Do not rebuild this idea
> without new evidence.

Introduce a minimal common interface pair (e.g. `Codec<T>` / `Validator` with a shared
`Violation` result type) adopted by `ConfigCodec`/`ConfigSafetyValidator` and
`PipelineCodec`/`PipelineValidator`; move genuinely shared encode/decode/violation-formatting
code down. **Interface-only unification** — validation *rules* stay where they are (spec vs safety
vs pipeline are different concerns; the overlap is the shape, not the rules). Skip if S-grounding
shows the shared surface is under ~50 lines — an interface for its own sake is the over-abstraction
CLAUDE.md §2 bans.

### S6 — God-class decomposition (imperative → dispatch tables)

> **STATUS: grounded 2026-08-27 — per-class verdicts.**
> **`MaintenanceJob` SPLIT (in progress):** ~14 independent tasks behind one `task` switch
> (`:163-192`); six already extracted (`StorageTrendTask`, `BackupTask`, `MetadataValidateTask`,
> `PartitionCompactor`, `ReferenceCompactor`, `MaterializeTask`) — finish the pattern: one
> package-private task class per in-file task, `MaintenanceJob` shrinks to the dispatcher; no
> registration/SPI change; `MaintenanceLibraryTest` (1067 lines) passes unmodified.
> **`PipelineConfigParser` LEAVE:** the dispatch-chain hypothesis was WRONG — it is sequential
> section parsing (one method per section) over one authored shape; the only kind-switch is a
> validator that stores `(kind, config)` verbatim. Multiple parse fallbacks are pinned to operator
> decisions (id-over-name, active/template default-off, steps-vs-legacy mutual exclusion,
> list-arity refusal, `raw.get` for description). A registry would add indirection for nothing.
> **`JobService` LEAVE:** a genuinely multi-responsibility job-host façade; the only separable
> cluster is the ~300-line run-execution engine (`:880-1177` → a possible `JobRunExecutor`) —
> extract ONLY if someone actually needs it; everything else reaches across the whole class.
> **`PipelineConfig` (1716): not yet grounded** — same authored-config heartland as its parser;
> ground before touching, expect LEAVE-shaped findings.

Per class, ground first, then:
- `PipelineConfigParser` (1616) / `PipelineConfig` (1716): if grounding confirms
  switch-on-type-string chains, refactor to a registry of parser functions
  (`Map<String, Function<MapView, Node>>`-style dispatch) and split (de)serialization from the
  model. ⚠ This is the AUTHORED-config heartland — parser fallback derivations are pinned by
  operator decision ([[three-owed-decisions-shipped]]); behavior-preserving means fixture
  round-trips, not just green units.
- `MaintenanceJob` (855): split bundled unrelated tasks into one class per task behind the
  existing job SPI.
- `JobService` (1372) / `ObjectService` (1358): extract cohesive collaborators only where a seam
  is obvious after reading; otherwise leave — bulk alone is not a defect.
- ⛔ `CollectorService` (1693): decomposition CLOSED won't-do (composition-root facade, 6 focused
  test files). Only S7 touches it, and only if the operator opts in.

### S7 — OPTIONAL, operator-gated: explicit composition root

The wiring-order fragility in `CollectorService`'s constructor (~60 positional `new`s referencing
`this::` handles) is real, but extracting a composition-root class brushes against the closed M2
decision. **Do not start without an operator yes.** If approved: a pure `Wiring` class that builds
the collaborator graph and hands it to a slimmer constructor — no DI framework, same manual style.

## Explicitly out of scope

- AI/model modules: `inspecto-agent`, `inspecto-agent-hosted`, `inspecto-intelligence` (and the
  `assist` package's AiDraft surfaces).
- The module map itself (reactor is settled; fp-query/job/enrich split stays deferred).
- `asn-parser` (separate reactor), `inspecto-deploy` (not a module), `inspecto-ui`.
- pom-level dependency pruning: the inventory found no removable module edges; third-party
  versions are already parent-managed. "Reduce dependencies" is delivered by S1/S2/S4 removing
  duplicated in-code plumbing, and by any module dep that the full reactor proves unused after a
  sweep (check per playbook rule 4).

## Verification & sequencing rules

1. One slice = one or more small commits, each gated on the FULL reactor (never `-pl` without
   `-am`; never two Maven runs on one tree) + both node guards + vocabulary-clean naming.
2. Relocations keep package names where possible (playbook rule 2, `git mv`).
3. Before moving any "unused-looking" helper, grep the READ path — config/keys written but never
   read have nearly shipped twice ([[written-but-never-read-config]]).
4. Suggested order: S1 → S2 → S5-grounding → S3 → S4 → S6, with S7 parked. S1/S2 are safe warm-ups
   that also create the homes S3/S4/S6 move code into.
5. Each finished slice: distill durable facts into the matching OKF concept, then archive this
   plan section per the docs lifecycle.
