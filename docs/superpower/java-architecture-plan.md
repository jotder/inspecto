# Java architecture reorganization plan (Phase 2 — coupling, modules, style)

Goal (operator, 2026-08-26/27): the codebase got complex for iterative development and hard to
understand and maintain. Simplify Java design, framework code and module organization; prefer
functional style; move reusable functions to utils; reuse code; reduce dependencies; keep it
modular; use generics where applicable. **Exclude AI/model modules.**

## Why a second plan exists

[Phase 1](java-simplification-plan.md) is complete: 9 commits, `d5791116`…`f211b455`, baseline
3630 → **3657/0/0/5**, zero behavior change. It consolidated ~60 duplicated helper copies into
`Values`/`SqlBuilder.quoteIdent`/`RouteErrors`/`SpiSlot`, split `PipelineRoutes` (1677 lines) and
`ConfigRoutes` into seven cohesive route modules, and finished `MaintenanceJob`'s half-done split.

**It did not reduce coupling, and that is measurable.** Graph nodes went 30,129 → 30,231, edges
79,963 → 80,264, communities 1,129 → 1,116, and the god-node list is the *same ten classes with
slightly higher degree* (measured again 2026-08-27 at `311a4523`).
Phase 1 optimized file length, duplication, and per-file responsibility. None of those are
connectivity. This plan targets the structural complexity Phase 1 deliberately left alone.

#### 🔴 …and that judgement was made with a metric that cannot reward decomposition (2026-08-27)

Track 1 of *this* plan finished, and the graph moved the same direction: nodes **30,231 → 30,265**,
edges **80,264 → 80,353**, `CollectorService` still the top god node. By the paragraph above, this
arc "did not reduce coupling" either.

**That reading is wrong, and the metric is why.** Extracting a 100-line block into a named method
*necessarily* adds one node and at least one call edge. Declaring a role interface adds a node and one
edge per implementor and per conversion. Both changes make the code more navigable and both are
**guaranteed to move node and edge counts upward.** A metric that structurally penalises
decomposition cannot be used to judge decomposition.

The one number that did move in the expected direction is **communities: 1,116 → 1,182 (+66)** — more,
smaller, better-separated clusters. If any single graph figure is worth watching for this class of
work, it is that one, not raw node/edge count.

⚠ Recorded prominently because the next shift will otherwise re-run the census, see counts up again,
and conclude the arc failed — which is exactly the inference this paragraph invited. **Judge
decomposition by what a reader must hold in their head** (`parse()` 801 → 280) **and by cycle/edge
facts that were checked by hand** (Phase A cut `intelligence.context → service`; C3 stayed at four),
not by the size of the graph.

## The reframe that drives every decision below

**Node degree is not complexity — fan-in and fan-out mean opposite things.**

| Class | fan-in (files) | fan-out (`com.gamma` types / imports) | verdict |
|---|---|---|---|
| `CollectorService` | 58 | **70 / 30** | genuinely knows about too much — the only clear case |
| `ObjectService` | 24 | **29 / 24** | high work, few dependents (but see Phase B: not separable) |
| `JobService` | 38 | **26 / 18** | multi-purpose host facade by design |
| `ControlApi` | 37 | 8 / 6 | router — and its real coupling is small |
| `PipelineConfigParser` | 17 | 6 / 4 | long, but barely coupled outward |
| `ComponentStore` | 54 | 4 / 3 | healthy: reuse |
| `PipelineConfig` | 93 | **3 / 2** | high reuse, almost NO outward coupling |
| `ApiContext` | 77 | 2 / 2 | healthy: reuse |

🔴 **CORRECTION (2026-08-27).** An earlier version of this table reported fan-out as "distinct
capitalized identifiers in the file" — `CollectorService` 346, `PipelineConfig` 297, `ControlApi` 305.
**That metric is near-worthless for coupling: it counts a class's OWN nested type names.**
`PipelineConfig` scored 297 while referencing just **3** `com.gamma` types — it is a large record full
of its own nested records, so its outward coupling is nearly nil, and the old table's verdict ("one
record carrying every concern → knows about too much") was simply wrong. Measure fan-out as distinct
comment-stripped `com.gamma.*` types (imports + inline FQNs), never as identifier count. By the
correct metric only **three** classes coupled meaningfully outward, and the ranking barely resembles
the size ranking. This does not change Phase B/F's LEAVE verdicts — those were decided on code
structure, not these numbers — and it strengthens the case for leaving `PipelineConfig` alone.

- **High fan-in is reuse and we want it.** `PublicApi` (an annotation), `apiUrl()`,
  `apiErrorMessage()` are high-degree *because* one implementation serves many callers.
- ⚠ **Consolidation RAISES degree.** Phase 1 turned ~60 helper copies into single nodes with ~60
  inbound edges each. Optimizing for low degree would mean re-duplicating them. Duplication and
  connectivity pull in opposite directions — never trade one for the other blindly.
- **High fan-out is the real complexity** — but measure it correctly (see the correction above).
  On the proper metric the whole codebase has only three classes above 15 outward types.

### Re-measured after Track 1 (2026-08-27, `311a4523` → `5db51a00`) — and the ceiling

Same script both refs (`assets/fanmatrix2.py`, kept beside this plan so the numbers are reproducible), so the deltas are trustworthy. Run it against a `git archive` extract of the old ref and against the working tree.
`fanOut plan` = the corrected metric above. `fanOut +pkg` adds **same-package** types used by simple
name, which the plan's metric cannot see.

| Class | fan-in (main) | fan-in (all) | fanOut plan | fanOut +pkg | imports |
|---|---|---|---|---|---|
| `CollectorService` | 25 → **25** | 148 → **148** | 70 → **70** | 83 → **84** | 30 → **30** |
| `ObjectService` | 8 → 8 | 21 → 21 | 29 → 29 | 34 → 34 | 24 → 24 |
| `JobService` | 12 → 13 | 21 → 22 | 26 → 26 | **65 → 65** | 18 → 18 |
| `ControlApi` | 2 → 2 | 116 → 116 | 8 → 8 | **73 → 73** | 6 → 6 |
| `PipelineConfigParser` | 1 → 1 | 2 → 2 | 5 → 5 | 7 → 8 | 20 → 21 |
| `ComponentStore` | 38 → 38 | 64 → 64 | 4 → 4 | 5 → 5 | 3 → 3 |
| `PipelineConfig` | 78 → 79 | 188 → 189 | 3 → 3 | 6 → 6 | 2 → 2 |
| `ApiContext` | 70 → 70 | 73 → 73 | 2 → 2 | 8 → 8 | 2 → 2 |
| `ReadModel` *(new)* | — → 7 | — → 7 | — → 6 | — → 8 | — → 6 |

🔴 **Track 1 moved this matrix by essentially nothing, and `CollectorService`'s net-zero is exact, not
approximate.** Diffing the fan-in file sets: `ContextBroker.java` **dropped**, `ReadModel.java`
**added**. Phase A converted six collaborators and removed exactly one file from the fan-in set — then
the interface declaring `List<CollectorService.PipelineView>` put one straight back.

⚠ **The plan's fan-out metric is blind to same-package coupling, and that is where the coupling now
is.** `ControlApi` reads 8 by the plan's metric and **73** counting the route modules it wires;
`JobService` 26 vs **65**. The claim "only three classes above 15 outward types" is an artifact of the
measurement, not a fact about the code.

#### How far this can actually go — `CollectorService`'s 25 fan-in files, classified

| Bucket | Files | Gate |
|---|---|---|
| references it **only for a nested type** (`PipelineView`/`PipelineRun`/`InboxStatus`) | **9** — `DataSourceRoutes`, `PipelineListRoutes`, `RunRoutes`, `OperationalActions`, `InspectoTools`, `ReportService`, `DataSourceBundleResolver`, `MetricsService`, `ReadModel` | ⛔ **decision #4** — see below |
| mixed (nested + real) | 1 — `PipelineSupport` | same |
| genuinely needs the class | **15** — 5 composition-root (`ServiceBootstrap`, `ServiceStores`, `SpaceBootstrap`, `SpaceContext`, `SpaceManager`), `ApiContext`/`ControlApi`, and 8 agent/SPI | SPI 8 gated on **decision #1**; the rest **irreducible** |

🔴 **The obvious lever was thought NOT free — and the reason was wrong.** Promoting the three nested
records to top-level drops fan-in **25 → 16** with no signature changes. It was gated as **decision #4**
because `CollectorService` is **`@PublicApi(since = "2.2.0")`** (`:86`), so its public nested records
looked published *by containment*.
⚠ **CORRECTED 2026-08-27 (second pass) — the gate was never real.** `CollectorService` does **not exist
in any release on `master`'s ancestry**: at `v3.11.0`, the newest ancestor tag, the type is
`com.gamma.service.SourceService`, renamed 2026-07-14 as a deliberate "breaking, NO version bump"
(GLOSSARY §13). The `since = "2.2.0"` records when the *element* became public API, not that today's
FQN ever shipped. The promotion was correct to make, but it never needed a grant.

**Realistic ceiling, in order:**
1. ~~Today, ungated: ~0.~~
2. ✅ **Decision #4 GRANTED and SHIPPED 2026-08-27** — `PipelineView`/`PipelineRun`/`InboxStatus`
   promoted to top-level `com.gamma.service` records. **fan-in 25 → 16, exactly as predicted**
   (9 files dropped: `DataSourceRoutes`, `PipelineListRoutes`, `RunRoutes`, `OperationalActions`,
   `ContextBroker`, `InspectoTools`, `ReportService`, `DataSourceBundleResolver`, `MetricsService`).
   Safe on version — and more safely than recorded here at the time. ⚠ **`v3.12.0` is NOT the last
   release on this line**: it is not an ancestor of `master` (divergent lineage). The newest ancestor
   release is **`v3.11.0`** (2026-06-03, 256 `.java` files), which contains no `com.gamma.service`,
   `consignment`, `ops` or `pipeline` package at all. Master is `4.0.0-SNAPSHOT` and the `v4.0.0` /
   `v4.0.0-RC1` tags were deleted 2026-08-17 (BRANCHING §1), so **nothing after 3.x has shipped** and
   4.0.0 *is* the pending major.
   🔴 **But the `report → service` EDGE did not move.** The promoted records live in
   `com.gamma.service` too, so `ReportService` swapped a `CollectorService` import for a
   `PipelineView` import — **a different holder, the same edge** — and it still imports
   `EnrichmentService` independently. ⭐ **Promoting a nested type removes the holder, not the edge,
   unless the promoted type also leaves the package.** Putting the records in a neutral package was
   considered and rejected: `EnrichmentService` holds the edge regardless, so it would have bought a
   new package for no measured gain.
3. **Decision #1 granted** (narrow the SPI `init`): a further ~8 agent/SPI files → fan-in ≈ **8**.
4. **Floor ≈ 7** — the composition root plus `ApiContext`/`ControlApi`, which legitimately need the
   full surface.
5. **fan-out 70 is the one number that does not move at all** without relocating responsibilities out
   of the class — i.e. the `CollectorService` decomposition that `BACKLOG.md` §6 closed as **won't-do**
   ("already reads as a composition-root/facade; no god-class emergency"). Adding interfaces cannot
   reduce it, and Phase A demonstrated that empirically.

⚠ **Census hazard found while measuring:** `.claude/worktrees/` holds a **full second checkout pinned
to an old commit** (1,437 `.java` files). Including it **doubles every tree-wide count** and mixes
stale sources in — the first run of this table reported exactly 2× on every fan-in. Exclude
`.claude/`, `.git/` and `target/` in any repo-wide census.

## Binding principles (learned the hard way in Phase 1)

1. **Ground before planning. Five of Phase 1's seven slices were refuted by reading the code** —
   the premises came from size and naming, which lie. Every phase below starts with a grounding pass
   that is allowed to return LEAVE, and a LEAVE with evidence is a successful outcome.
2. **Never split by line count.** Phase 1 proves it does not move connectivity.
3. **A matching body is not a matching family — the call site decides.** This trap appeared three
   times (`RuleTemplate.str`, `ComponentPreview.intOr`, `DbExportConnector.quote`).
4. **Behavior-preserving.** Every commit gates on the full reactor
   (`mvn -o clean test -Pedition-enterprise`, currently **3657/0/0/5**) plus both node guards.
   Contract tests pass *unmodified* — a refactor that needs its tests edited is a redesign.
5. **Framework-free stays binding** (JDK HttpServer, manual DI, ServiceLoader). Simplifying
   framework code means consolidating our own plumbing, never adopting a framework.
6. **Reactor shape is settled.** `okf/backend/modules/reactor.md` is as-built; module *extraction*
   is not in scope (see Phase C for what is).

## Explicitly out of scope

- AI/model modules: `inspecto-agent`, `inspecto-agent-hosted`, `inspecto-intelligence`, and the
  `assist`/AiDraft surfaces.
- `asn-parser` (separate reactor, own groupId), `inspecto-deploy` (not a Maven module),
  `inspecto-ui` (frontend — its own conventions live in the `angular-ui` skill).
- Re-litigating: M2 `CollectorService` decomposition (closed won't-do), S7 wiring extraction
  (refuted 2026-08-27 — 15 `this::` bindings saturate the constructor by design), the
  fp-query/fp-job/fp-enrich module split (deferred, nobody has asked).

## Phases

### Phase B — `ObjectService` decomposition — ⛔ CLOSED as LEAVE (grounded 2026-08-27)

**This was the phase I recommended first, on its fan-in/fan-out profile. Reading the code refuted
it** (and the profile itself was overstated — its real fan-out is 29 `com.gamma` types / 24 imports,
not the 179 identifier-count first quoted). The numbers pointed at a class doing a lot that few depend on; the structure
shows why that does not make it separable:

1. **The shared primitives are pervasive, not incidental.** Every mutating cluster
   (`open`/`transition`/`assign`/`watch`/`sla`/`link`/`merge`/`split`/`applyTag`) funnels through
   `store`, `EventLog.current().emit(...)`, and `require(id)` (`:1287`) / `commit(...)` (`:1291`).
   Extracting any cluster leaves it calling back into `ObjectService` or duplicating those
   primitives. Only `queues`, `caseRules`, `links`, `notes` are single-cluster-owned fields.
2. **The largest dependent already spans every cluster.** `ObjectRoutes` calls **25 of ~30 public
   methods** across CRUD, workflow, assign/watch, links, merge/split, notes/RCA, analytics and case
   rules. A split does not reduce caller complexity — it makes that one caller hold three or four
   references instead of one. `CollectorService` straddles two clusters for the same reason.
3. **The cuts would not be acyclic.** `open()` calls the tag cluster's
   `autoApplyTagRules`/`adoptTags` (`:213`, `:217`); `evaluateCaseRule()` calls `open()` and
   `link()` (`:677-708`). `purge` (`:1227-1277`) touches five stores at once.

The one semi-separable cluster is `TagService` (`tags`/`tagRules`/`tagAssignments`, `:400-641`) —
`TagRoutes` uses only that surface — but `open()`'s call back into it makes it a facade-delegate
rather than a clean removal. **Not worth the churn.** ⛔ Do not reopen on the strength of the
fan-out number alone; that number is what pointed here in the first place.

### Phase A — `CollectorService` role interfaces — VIABLE, but the win is smaller than advertised

**Verdict: BUILD, with the payoff stated honestly.** ⚠ **It does NOT move `CollectorService`'s
fan-out (70 `com.gamma` types / 30 imports — the highest in the codebase)** — it still constructs and owns every field it always did. What it moves is
**fan-in**: 6–8 satellite collaborators stop compiling against a 1693-line class. That is a real
decoupling and testability win, not interface files for their own sake, but it is not the headline
number anyone will expect. Say so up front to whoever picks this up.

**Two corrections to my own framing, both from reading the code:**
- ⛔ The `MaintenanceJob` "host receives the facade" example I proposed is **wrong** — its `host`
  field is typed `JobService` (`MaintenanceJob.java:98`). No maintenance task receives
  `CollectorService`.
- ✅ The `this::` handles (19 sites) are **already** single-method interfaces — arity-1, the narrowest
  possible. **None need any change.** Three of them are already the exact proposed pattern in
  miniature: `IncidentAccess.over(this::objects)`, `SchemaAccess.over(this::componentRegistry)`,
  `ConsignmentStatusAccess.over(this::loadedPipelines)` (`:423,425,428`) — hand-rolled role adapters
  that prove the idea and set the house style.

**The shape:** one cohesive `ReadModel` interface — **not** five tiny interfaces, because the consumers
overlap heavily (`pipelines`/`configFor`/`statusStore` each appear in 4+).

⚠ **The member list is 10, not the ~13 guessed here** (corrected 2026-08-27 by enumerating what the six
consumers actually call). The guess included five accessors **no consumer uses** — `catalog`, `reports`,
`configSource`, `objects`, `eventLog` — and **omitted one that is used**, `browsableStores`
(`InspectoTools:83`, via a `service::browsableStores` method-ref). Declaring the five unused ones would
defeat the point: a role interface that carries members nobody calls is just the god class with fewer
lines. The real union:

| Consumer | Calls |
|---|---|
| `ContextBroker` | `events` |
| `OperationalActions` | `jobService`, `pipelines` |
| `DataSourceBundleResolver` | `pipelines`, `configFor`, `pathFor` |
| `MetricsService` | `eventBus`, `pipelines`, `configFor`, `statusStore` |
| `ReportService` | `pipelines`, `configFor`, `statusStore`, `enrichmentService` |
| `InspectoTools` | `pipelines`, `events`, `jobService`, `configFor`, `statusStore`, `dataRoot`, `browsableStores` |

Union = `pipelines`, `configFor`, `pathFor`, `statusStore`, `events`, `eventBus`, `jobService`,
`enrichmentService`, `dataRoot`, `browsableStores`.

⚠ Two of the six — `MetricsService` and `DataSourceBundleResolver` — are **already in
`com.gamma.service`**, the same package as `CollectorService`. Their conversion is a pure
signature-honesty win with no cross-package effect at all. Worth knowing before anyone counts them
toward a decoupling claim.

**Receivers to convert (6 single-file edits, one per commit):** `ReportService:45-47`,
`MetricsService:30-33`, `DataSourceBundleResolver:36-39`, `ContextBroker:34-36` (needs only
`events()`), `InspectoTools` static params, `OperationalActions:131-216`.

**Deliberately NOT converted:**
- ⛔ **The `AssistAgent`/`IntelligenceAgent` SPI `init(CollectorService)`** — a published
  `ServiceLoader` contract. Narrowing it is a **compatibility break for third-party implementors**,
  a categorically different change from internal narrowing. Out of scope for this phase.
- `ApiContext`/`ControlApi` (~40 members across 50+ route files) and `SpaceContext` (`start`/`close`)
  legitimately need the mutation and lifecycle surface. Excluding them is deliberate.
- `InspectoPack`/`InspectoToolProvider`/`Investigator` are pure passthrough and call nothing —
  threading a narrower type through them buys only signature honesty.

**Zero-churn path (the only viable one):** declare `CollectorService implements ReadModel` first —
green, no behavior change, no test edits — then convert one collaborator's field type per commit.
`DataSourceBundleResolverTest` and `ContextBrokerTest` construct these with a real
`CollectorService`, so they pass unmodified *only* under this approach.

**⛔ Interaction with Phase C3 — REFUTED by grounding (2026-08-27). Phase A cuts NO package edge.**

The paragraph that stood here claimed Phase A cuts C3's `report` edge, shrinking the cycle from four
packages to three, and that `ReadModel`'s package was the lever that decided it. Reading the code
refuted both halves. **`com.gamma.report` → `com.gamma.service` has three independent holders**, and
`ReadModel` removes only one of them:

| # | Holder | Site | Survives a `ReadModel` conversion? |
|---|---|---|---|
| 1 | `CollectorService` (the field itself) | `ReportService.java:6,45` | removed by Phase A |
| 2 | **`EnrichmentService`** | `ReportService.java:5`, called at `:208` | ✅ survives — an unrelated `com.gamma.service` type |
| 3 | ~~`CollectorService.PipelineView`~~ | `ReportService.java:126,173` | ✅ **REMOVED 4.0.0** by the record promotion (decision #4) — but the EDGE survives, see below |

Holder 3 is the decisive one and it generalises. **`pipelines()` returns `List<PipelineView>`, and
`PipelineView` is a record nested *inside* `CollectorService`** (`CollectorService.java:257`). Every
caller spells the element type out — `for (CollectorService.PipelineView v : service.pipelines())` —
so any file calling `pipelines()` imports `CollectorService` no matter what type the *receiver field*
has. Five of the six conversion targets call `pipelines()`.

**Consequence: `report → service` is uncuttable by Phase A, so C3 stays at four packages, not three.**
Cutting it would additionally require promoting `PipelineView`/`PipelineRun` out of `CollectorService`
and abstracting `EnrichmentService` — a redesign, which by decision #2's default means *stop and
record*, not build.

#### ✅ DECIDED 2026-08-27 — `ReadModel` lives in `com.gamma.service`

Task 1 of the A sequence is answered, and the reasoning is *not* "it was the default":

1. **The structural argument for an exotic package is void.** It existed only to cut C3's `report`
   edge, which the table above shows cannot be cut this way.
2. **Every alternative home is actively harmful, not merely neutral.** The read surface is expressed
   in `com.gamma.service`'s own vocabulary — `PipelineView` (nested in `CollectorService`),
   `Optional<EnrichmentService>`. A `ReadModel` declaring those members *must* import
   `com.gamma.service` wherever it lives. The only packages shared by the consumers are
   `com.gamma.etl` and `com.gamma.job` — and `service → etl` and `service → job` already exist, so
   hosting `ReadModel` in either would **create a new cycle** to buy nothing. That is strictly worse
   than the status quo.
3. **What Phase A still delivers is a fan-in and testability win, and only that.** Six collaborators
   stop compiling against a 1693-line, 60+-method class and compile against a ~13-method role; a fake
   `ReadModel` becomes possible in tests where only a real `CollectorService` works today. That is
   worth doing. It is not a package-graph win, and nobody should report it as one.

⚠ This decision is cheap to keep and expensive to revisit only in the sense that six importers change
— but since the package is the one they already import, a later move is a pure rename, not a
re-architecture. The thing that would be expensive is having built it in `etl`/`job` first.

### Phase F — `PipelineConfig` per-concern split — ⛔ CLOSED as LEAVE (one optional carve-out)

**"The biggest prize" is mostly already claimed, and the rest is not worth the risk.**

1. **The split is ~80% done already.** Twelve nested records plus five legacy-block records carry
   almost every concern (`Identity`, `Dirs`, `Processing`, `CsvSettings`, `Sink`, `Step`, `Intake`,
   `Unpack`, `Collector`, `Dedup`, `Summarize`, `Join`, …). The class comment (`:18-22`) documents
   this as a deliberate accepted tradeoff. Only *type-level* separation remains — and imports are
   cheap. The real coupling is call-site knowledge of ~12 accessor names, which per-concern types do
   not remove.
2. **The consumer histogram does not support it.** Of 94 consumers: 17 touch zero concerns (type
   pass-through), 35 touch exactly one — **so only 37% would see their surface shrink** — while 42
   (45%) touch two or more and would simply import several per-concern types instead of one. The
   heaviest consumers are the worst case: `PipelineLift` touches 11 of 12 concerns,
   `CollectorProcessor` 8 of 12.
3. **`prepare()` (`:1509-1594`) must stay monolithic, and it straddles nearly every concern by
   design.** Its fail-closed arming guards reason about *combinations* — "route is set AND active AND
   sinks has >1 entry". Distributing that logic per-concern is precisely the drift the guards exist to
   prevent. A split would force it to take many per-concern parameters: mechanical, but all risk and
   no gain.
4. **Blast radius meets a documented failure mode.** The write path lives elsewhere
   (`PipelineEditable.lower()`, `PipelineCodec`), so on-disk keys are not *directly* at risk — but
   every past incident in this area (unmodelled-key loss, stringified-config data loss, TOON
   truncation) came from an "obviously equivalent" restructuring silently dropping a key. A split
   re-opens every accessor site plus the `Builder` (`:1598-1715`) and three constructors
   (`:1193-1332`) — each one a place a field can go missing, across 94 consumers.

**The one safe increment, if anyone wants it later:** extract the **`Collector` acquisition subtree**
(`:536-774`, `Collector` + 9 sub-records) to a standalone top-level type, leaving
`PipelineConfig.collector()` as a delegating accessor. It is fully self-contained, has zero
cross-references into other concerns' fields, its ~93 call sites are concentrated in
acquisition-specific files, **`prepare()`'s arming guards never read it**, and it touches zero
on-disk keys. Independently verifiable. ⛔ Everything else — especially `Dedup`/`Summarize`/`Join`/
`route`/`steps`/`outputStore`/`active` — stays put: they are jointly read by `prepare()`.

⚠ `Identity` is worth knowing about: `identity()` is called ~166 times across 60 files
(`identity().pipelineName()` alone at 123 sites). It is already the most self-contained record in the
file, and a split would not disturb it either way.

### Phase C — Module & package organization: **three undocumented cycles** ⭐ highest value

**The census found the finding this whole plan was looking for: `okf/backend/modules/reactor.md` is
STALE.** Its `event↔metrics` record still holds exactly, but its "`pipeline` is now a clean base"
layering claim no longer describes the code — three strongly-connected components exist that the doc
does not mention, all created by packages added after the 2026-07-22 measurement:

| # | SCC | Edges holding it together |
|---|---|---|
| C1 | `{ops, ops.workflow, ops.tag, ops.link, ops.note, ops.findings, pipeline}` | `ops.*` all depend up on `ops`; `ops → pipeline`; `pipeline → ops.findings → ops` closes it |
| C2 | `{catalog.spi, catalog, alert, pipeline.exec, job, consignment, enrich}` | `job ↔ consignment` (both directions), `catalog ↔ catalog.spi`, `alert → catalog`, `catalog → enrich → consignment`, `pipeline.exec → consignment` |
| C3 | `{report, intelligence.spi, assist.spi, service}` (core) | `report`/`assist.spi`/`intelligence.spi` all import `service.CollectorService`, which imports back into all three |

**Why this is the highest-value phase:** cycles are the one form of coupling that is unambiguously
harmful — they defeat layering, block extraction, and make change propagate unpredictably. And the
reactor playbook proves they are usually cheap: every prior SCC here was held by **two or three
edges**, and cutting them was a relocation, not a redesign.

**`consignment` is the new tangle point** — it appears in neither the doc nor the prior analysis, and
sits in the middle of C2 via a two-way edge with `job`.

**C3 has a proven remedy already used in this repo:** the `job→report` cycle was cut by the
`ReportRunner` SPI (`report` now depends *down* on `job`). C3 is the same shape — three packages
importing the concrete `CollectorService` while it imports back — but the inversion was never applied
consistently. ⚠ This interacts with Phase A: role interfaces are exactly the mechanism that would
cut C3, so **scope A and C3 together, not separately.**

**STATUS 2026-08-27 (third pass): step 1 SHIPPED; the blocker was a false premise, not a decision; and
BOTH C1 and C2 are now CUT** (`cf48d335`, `15205362`). Two SCCs remain in engine main sources, both
structural: the deliberate `catalog ↔ catalog.spi` pair, and an `ops` parent/child residual the census
never reported. As-built in [`reactor.md`](../okf/backend/modules/reactor.md).

1. ✅ **`reactor.md` corrected** — a dated "Re-measured 2026-08-27" section supersedes (does not
   rewrite) the true-but-historical 2026-07-22 layering claim, with all three SCCs, their exact
   holding edges, and what is *not* a cycle (`catalog ↔ catalog.spi` is the deliberate SPI pair;
   `job → consignment` is legitimately one-way).
2. ~~⛔ **C1 and C2 cuts BLOCKED — every cycle-cutting class is `@PublicApi`.**~~
   ✅ **REFUTED 2026-08-27 (second pass). There is no API break, so there is nothing to grant.**
   The blocker read the annotation and stopped there. Measured against the tags instead:

   | Class | Marked | At `v3.11.0` (newest ancestor release) |
   |---|---|---|
   | `ConsignmentProcessJobType` | `@PublicApi(5.0.0)` | **absent** — no `com.gamma.consignment` package exists |
   | `ProcessorContext` | `@PublicApi(5.0.0)` | **absent** — same |
   | `AnnotationKinds` | `@PublicApi(4.9.0)` | **absent** — no `com.gamma.ops` package exists |
   | `FindingsSpec` | `@PublicApi(4.6.0)` | **absent** — same |

   **No `v4.x` or `v5.x` release has ever existed**, so a `since` of 4.6.0/4.9.0/5.0.0 named a version
   that was never cut; all four were corrected to `4.0.0` in the repo-wide sweep. Relocating a type
   that has never been published breaks no consumer, and the stability policy's "only on a major bump"
   condition is satisfied by the pending 4.0.0 regardless. **C1 and C2 are ordinary refactors.**
   ⚠ The trap this produced is worth more than the cuts: `@PublicApi` marks *intent to publish*, and
   on a trunk that has not released its major, intent is not exposure.
   🔴 **This is the THIRD independent time the premise `@PublicApi ⇒ breaking ⇒ bump` was written into
   a plan and then refuted** — Source→Collector (GLOSSARY §13, "breaking, NO version bump"), <!-- vocab-allow: names the Source→Collector rename itself -->
   `ConsignmentProcessor` (BACKLOG §4: *"the premise came from this row and was never tested"*), and
   now Phase C. The policy is stated once in
   [`okf/backend/control-plane/api-stability.md`](../okf/backend/control-plane/api-stability.md)
   §*Release baseline*; check the tag, not the annotation.
   - **C1 still has a second, independent path** — constant-ownership inversion (move `TYPES`/`TIERS`
     canonical values to `pipeline`, `FindingsSpec` delegates, both edges then point `ops → pipeline`).
     That is a design change rather than a relocation, so it remains **decision #3** — a question of
     which design is better, no longer a way to dodge an API break.
3. C3: partly cuttable via Phase A — see there; its last two edges need decision #1, whose cost is
   **also smaller than recorded** — see that decision.

🔴 **The measurement trap this phase produced, worth more than the cuts:** 31 of the engine's 135
`@PublicApi` classes write the annotation **fully-qualified**, so `grep "@PublicApi"` under-reports
the published surface by ~23%. `AnnotationKinds` was wrongly cleared for relocation that way before a
second check caught it. Always match `@(com\.gamma\.api\.)?PublicApi`. ⛔ **Check the stability marker
BEFORE planning any relocation** — cohesion and import analysis will happily propose moving a
published type.

⚠ **Playbook rules that apply and have burned this repo before:** strip comments before counting
edges (javadoc `{@link}` is not a compile edge and once produced a phantom blocker); an import-line
grep misses fully-qualified inline calls; gate on the FULL reactor, never one module.

### Phase D — Functional style, at measured sites

The census measured rather than guessed. Two genuinely worthwhile groups and one non-target:

> **D1 COMPLETE (2026-08-27) — 16 conversions total; the census's "~51 sites" was off by ~3×.**
> Engine batch: a fresh AST-ish scan of all 317 `new ArrayList<>()` allocations in `inspecto-engine`
> found **22 raw matches → 6 genuinely convertible sites, not the claimed ~38.** Combined with the
> inspecto batch (10 of a claimed ~13, from files the census mostly named wrongly), **the census's
> accumulator-loop detector over-reports by roughly 3–6×.** ⛔ Do not size any future sweep from its
> figures — re-sweep and count.
> **The mutability trap is common, not exotic: 6 sites hit it.** `ReconRoutes:176` (inspecto), plus
> `AlertService:124` (adds after the loop, then `List.copyOf`), `RecipeCompiler:152` (`addAll`
> after), `MeasureCompiler:118` (a second loop appends), `GuardedSummaryEmitter:131` (later branches
> add, plus an early return), `ComponentPreview:589` (accumulated across six blocks). Every one would
> have compiled and thrown `UnsupportedOperationException` at runtime.
> ⚠ **One conversion was made and then REVERTED on readability** — `ReconService:343` (`joinChain`):
> the enclosing `for (int s = 1; …)` variable is not effectively final, so the lambda needed an extra
> `int side = s;` local. That reads worse than the loop it replaced, so the loop stays. This is D1's
> stated rule working as intended.
>
> **D1 STATUS (inspecto batch, 2026-08-27): 10 conversions across 8 files, reactor 3657/0/0/5.**
> 🔴 **The census's site list was mostly WRONG — of its 8 named candidate files, only ONE actually
> held the target shape** (and at different lines than cited). The real conversions came from a fresh
> sweep for single-statement `add` bodies. **Treat the "~38 sites in inspecto-engine" figure as
> equally unreliable** — re-sweep, do not work from the census list.
> 🔴 **The mutability trap fired for real:** `ReconRoutes:176` builds `measureNames` and then mutates
> it after the loop (`if (spec.includeRecordCount()) names.add(RECORDS)`). Converting it to
> `.toList()` compiles clean and throws `UnsupportedOperationException` at runtime. Skipped — and
> wrapping it in `toCollection(ArrayList::new)` would read worse than the loop it replaced.
> Other skips, all deliberate: multi-statement `LinkedHashMap` builders (a lambda block buys no
> readability, and JSON key order must stay `LinkedHashMap`), bodies that `throw` a 422/409/400
> mid-loop (validation is the loop's *job*), bodies throwing checked `IOException`, `instanceof`
> pattern variables that do not survive a `filter`→`map` split without a duplicate cast, and `Set`
> targets. **Readability is the whole point of D1 — a site that reads worse as a stream is a skip,
> not a conversion.**

**D1 — the accumulator-loop cluster (~51 sites): cheap, safe, high readability.** `new ArrayList<>()`
followed by a loop whose body is only `.add(...)` — a single `stream().map().toList()` expression.
Concentrated in `inspecto` route classes (~13: `BiTemplates:67/104`, `BundleRoutes:623`,
`ConfigPreviewRoutes:236-237`, `GeoRoutes:236`, `InvRoutes:235`, `LineageRoutes:86`,
`ParserRoutes:123`, `PipelineGraphRoutes:521`) and `inspecto-engine` (~38). Sweep one module per
commit. ⚠ Convert only loops that *purely* map; a loop that also filters, mutates outer state, or
short-circuits is not the same thing.

**D2 — the giant methods.** `PipelineConfigParser.parse()` was **801 lines** — the largest method in
the backend by a wide margin, now **477** (D2a tasks 1/3/4/8, 2026-08-27). ⚠ Phase 1 already
established the parser is *sequential section parsing*, so the fix is NOT a dispatch table: it is
extracting each `// ── section ──` block into a named private method. That is mechanical and
reviewable — **but only for the sections that do not share locals**, which is what the leak analysis
in the D2a breakdown settles. Half the sections do; those are a LEAVE.
⛔ The four follow-on candidates — `PipelineEditable.lower()`, `FindingsSpec` ctor,
`ConfigSpecs.pipeline()`, `JobRoutes.maskSecrets()` — are **all closed as LEAVE** (D2b, grounded);
two of them do not exist at the cited size. Do not re-schedule them from this paragraph.

**NOT a target — the big switches.** `MaintenanceJob`'s 20-case switch is now (post-Phase-1) a pure
dispatcher, which is the correct shape; `ControlApi:936` and `AuditTrail:140,143` are likewise
dispatch tables. A dispatch table is already the functional form. ⛔ Do not "improve" these.

⚠ The census's immutability detector (7 candidates) is explicitly its weakest signal — a
single-assignment heuristic that undercounts. **Do not act on it**; re-run with a real static-analysis
tool first if immutability is wanted.

### Phase E — Dependency reduction

**E1 — ✅ SHIPPED `045b6d41` (2026-08-27), and it was more than an unused dependency.** The
dependency tree showed `inspecto-etl` carrying **both Jackson majors**: `jtoon:1.0.9` brings
`tools.jackson.core:jackson-databind:3.0.4` (Jackson 3, new groupId) transitively — what JToon
actually uses — while the module separately declared `com.fasterxml.jackson.core:jackson-databind`
(Jackson 2) with the stale comment "for Map conversion used by JToon patterns". Neither was
referenced by any main or test source. Removing it also drops `jackson-core` +
`jackson-annotations` 2.x from the compile classpath. Gated on `-Pedition-enterprise` with an
explicit check that both profile-gated modules **built** rather than skipping (3657/0/0/5).

**E1 does NOT generalize — audited and closed.** Every other module declaring Jackson 2 genuinely
uses it: `inspecto` (5 main / 103 test files), `inspecto-engine` (5/7), `inspecto-config` (2/0),
`inspecto-event` (2/0), `inspecto-util` (1/0). ⛔ No further Jackson removals; do not re-audit.

**E2 — do NOT touch the runtime-only deps.** The census correctly flagged rather than asserted:
`duckdb_jdbc` in `inspecto-util` (`DuckDbUtil:50` loads the driver by `Class.forName`) and in
`inspecto-event` (`ParquetEventStore:109` by JDBC URL), and `postgresql` in `inspecto-connectors`
(`DbConnections:62` by URL). These have zero imports and are all load-bearing. ⛔ An import-based
"unused dependency" scan is exactly how a working driver gets deleted.

**E3 — narrow-usage libraries are correctly isolated already**, not removal candidates: `logback`
(1 file, the single-owner appender), `nimbus-jose-jwt` (the optional security module), `opencsv`
(confined to `inspecto-util`). Recording this so nobody re-opens it as waste.

**Module-edge reduction: nothing to do.** The reactor is an acyclic DAG with no removable module
dependency — confirmed twice now. Dependency reduction here means in-code plumbing (Phase 1's work)
and E1, not pom surgery.

## Verification & sequencing rules

1. One phase = several small commits; each gates on the FULL reactor, never `-pl` without `-am`
   (a `-pl` run silently tests the stale sibling jar from `~/.m2`).
2. Never run two Maven builds on this tree at once — a concurrent `clean` produces
   "package does not exist" errors that look exactly like a broken dependency.
3. Relocations keep package names and use `git mv` so history survives.
4. Update the touchpoint docs in the SAME commit as the code — Phase 1 shipped three splits and left
   ~23 stale references (including broken links) that needed a catch-up commit.
5. Each finished phase distils its as-built facts into the matching OKF concept, then this plan is
   archived per the docs lifecycle.

## Sequence — DECIDED 2026-08-27 (supersedes the original value-per-risk ordering)

The first ordering was written before Phase C/D/E were grounded. Grounding shipped three items,
closed two, and **blocked every cycle cut behind a published-API decision** — so the sequence is now
governed by a different constraint: *what can proceed without an operator, and what must be asked
now because it has long lead time.*

### ✅ Done (this arc)

| Commit | Work |
|---|---|
| `10b370a8` | C-step-1 — `reactor.md` corrected; three cycles + their exact edges recorded |
| `045b6d41` | E1 — stale Jackson 2 dropped from `inspecto-etl`; **audited, does not generalize, E is closed** |
| `260c023a` | D1 (inspecto) — 10 loops → streams; the `ReconRoutes:176` mutability trap documented |
| `311a4523` | D1 (engine) — finishes the D1 sweep |
| `f7fd7f53` | **D2a task 1** — `parseCollector` out of `parse()`; 16 `Builder` fields → 1 |
| `a4f9938d` | **D2a tasks 3, 4, 8** — `parseSchemas`, `parseSteps`, `parseTransformBlocks`; `parse()` 801 → 477 |
| *(this arc)* | **D2a tasks 2, 5 + the un-listed unblocker** — `parseOutputAndSinks`, `parseParsing`→`Grammar`, `parsePlugin`; `parse()` 477 → **280**. D2a DONE. |
| *(this arc)* | **Phase A COMPLETE** — `ReadModel` in `com.gamma.service` + all 6 conversions; one package edge cut (`intelligence.context`), the C3 premise refuted and demonstrated |
| *(this arc)* | **D2b CLOSED** — 4 of 4 LEAVE; two candidates did not exist at the cited size |

### The governing principle for what remains

**Ask the decisions NOW, in parallel; do the undecidable-free work meanwhile.** The three blocked
items all wait on operator/product calls (a major-version window, an SPI compatibility stance) that
may take days and are not engineering questions. Serialising work behind them wastes the interval.
Nothing in the unblocked track depends on how they are answered.

### Track 1 — proceeds immediately, no decision needed

| # | Work | Status | Why in this position |
|---|---|---|---|
| 1 | **D1 engine batch** | ✅ `311a4523` | Finished the sweep D1 started. |
| 2 | **D2a — `PipelineConfigParser.parse()` → named section methods** | ✅ **DONE — 6 of 8 shipped, 2 grounded LEAVE** | Was the single largest method in the backend: **801 → 280 lines**, seven named methods, at the plan's own "~250" stop-and-reassess target. Tasks 6/7 are LEAVEs on shared-local evidence, not deferrals. |
| 3 | **A — `ReadModel` + 6 collaborator conversions** | ✅ **DONE — all 8 tasks** | ⚠ The old note here — that the package choice is "the only lever that lets Phase A also cut C3's `report` edge" — is **refuted**; Phase A cut exactly one package edge, and not that one. Shipped as the fan-in/testability win it actually is. |
| 4 | ~~**D2b — the remaining giant methods**~~ | ⛔ **CLOSED — 4 of 4 LEAVE** | Two of the four did not exist at the cited size (over-reported 30–80×); the two real ones are a shared-state method and a declarative literal, neither of which is an extraction target. |

**Why A comes after D2a rather than before:** both are safe, but D2a is *pure* extraction with a known
shape, while A introduces a new published-ish type whose package placement has a consequence
(C3). Doing the mechanical one first keeps the arc's momentum while leaving the judgment call for
when there is time to make it properly.

### Track 2 — was "blocked; ask now, build when answered". ⚠ **Re-scoped 2026-08-27 (second pass): the version blocker was false, so items 6 and 7 are ordinary work.**

| # | Work | Waits on | If the answer is no |
|---|---|---|---|
| 5 | **C1 cut via constant-ownership inversion** | ✅ **SHIPPED `15205362`** | This turned out to be the ONLY way to cut C1 — see item 6. |
| 6 | ~~**C1 + C2 cuts via relocation**~~ | ✅ **BOTH CUT 2026-08-27** — C2 `cf48d335`, C1 `15205362`. Verified 3657/0/0/5 on each. | ⚠ **"via relocation" held for C2 and was FALSE for C1.** `AnnotationKinds`' consumers all live under `ops.*`, so relocating it re-creates the edge; C1 needed item 5's inversion. As-built + the residual `ops` SCC: [`reactor.md`](../okf/backend/modules/reactor.md). |
| 7 | **C3's last two edges** | decision #1 (SPI narrowing) — **a design call, not a release-policy gate** | ⚠ **Corrected 2026-08-27:** C3 stays at **4 packages**. The old fallback ("shrinks 4 → 3 via Phase A") assumed Phase A cuts the `report` edge; it does not — `EnrichmentService` and (since `7eab72d4`) `PipelineView` — still `com.gamma.service` — hold it independently. If decision #1 is *no*, C3 is documented and unchanged. |
| 8 | *(optional)* **F carve-out — `Collector` subtree** | decision #5 | Nothing depends on it; the plan's own recommendation is not to build it unprompted. |

### Task-level breakdown of Track 1 (decided 2026-08-27)

Phase-level ordering is above; this is the intermediate task sequence *within* each remaining item —
what a shift actually picks up, and how it knows the task is done.

**Universal per-task definition of done:** full reactor `-Pedition-enterprise` at **3657/0/0/5**
exit 0 · both node guards green · **contract tests unmodified** · one commit per task.

#### D2a — `PipelineConfigParser.parse()` (801 lines → named section methods)

Grounded: the method carries **40 `// ── section ──` markers**. They are *not* equal work, and the
ordering below is by **extraction cost, not line count** — a section that produces one cohesive
value is a clean `private static X parseX(...)`; a section that scatters many locals into the final
constructor is not, and those come last or not at all.

| # | Task | Lines | Status / why this position |
|---|---|---|---|
| 1 | extract `parseCollector(...)` | ~119, 9 nested sub-blocks | ✅ **SHIPPED `f7fd7f53`.** Premise held: the 16 `Builder` fields mapped 1:1 onto the existing `Collector` record, so it returns one value AND the 16 fields collapsed to one. |
| 4 | extract `parseSteps(...)` | ~75 | ✅ **SHIPPED.** Zero leaks. Both pinned refusals moved verbatim by script. |
| 3 | extract `parseSchemas(...)` | ~83 | ✅ **SHIPPED.** Zero leaks. 6 params (`raw, proc, configDir, sourceLabel, declaredColumns, b`). |
| 8 | extract `parseTransformBlocks(...)` (duplicate check, dedup, summarize, join, map, route) | ~61 | ✅ **SHIPPED.** All six sections leak nothing; grouped as one concern, 3 params. The arming logic stayed in `prepare()`/`PipelineLift` as required. |
| — | extract `parseOutputAndSinks(...)` *(not in the original list)* | ~40 | ✅ **SHIPPED.** Both sections leak-free. Scheduled because it was the **unblocker**: it is what physically separated tasks 2 and 5. |
| 2 | extract `parseParsing(...)` (csv settings + unified `parsing:`) | ~107 | ✅ **SHIPPED**, but not as described — "self-contained" was wrong. It leaks five locals, so it returns a private `Grammar` record carrying them. |
| 5 | extract `parsePlugin(...)` | ~54 | ✅ **SHIPPED.** Takes the `Grammar` (5 params, not 9), unpacks it into locals of the original names, body byte-identical. |
| 6 | `parseProcessing(...)` (processing, batch caps, streaming, DuckDB, chunking, intake) | ~66 | ⚠ **Not clean.** `proc` leaks to nearly every later section; `batch`, `intake`, `unpack` leak too. Not "one `Processing`-shaped result". |
| 7 | `parseDirs(...)` (dirs + audit/manifest paths) | ~44 | ⚠ **Not clean.** `dirs` leaks forward. |
| 6 | `parseProcessing(...)` (processing, batch caps, streaming, DuckDB, chunking, intake) | ~66 | ⛔ **LEAVE.** `proc` is read by nearly every later section; `batch`, `intake`, `unpack` leak too. Not "one `Processing`-shaped result". |
| 7 | `parseDirs(...)` (dirs + audit/manifest paths) | ~44 | ⛔ **LEAVE.** `dirs` leaks forward. |
| 9 | **STOP and reassess** | — | ✅ **Done. `parse()` = 801 → 280 lines**, at the "~250" target the plan set. Seven named methods; the two remaining tasks are grounded LEAVEs. **The identity/gates head was left alone**, as the plan allowed — those sections set independent top-level locals and extraction would only move noise. |

**⚠ The `Lines` column no longer carries line numbers.** The originals (`:750-869` etc.) were correct
when written and are now all wrong — task 1 alone moved everything below it. Anchor on marker text.

##### The leak analysis — the tool this section actually needed

The plan's per-task verdicts ("self-contained", "cohesive", "one `Processing`-shaped result") were
eyeball judgements, and **four of eight were wrong**. What settles it mechanically: for each section,
which locals does it *declare* that are *read after it ends*? Zero ⇒ clean extraction. The script is
`scratchpad/leak_analysis.py`; the answer for `parse()`'s 40 sections:

| Leaks | Sections |
|---|---|
| **none — clean** | identity, activation gate, template gate, Catalog Stream membership, Stage-2 output store, entry-node trigger, batch audit/manifest, streaming plugin engine, DuckDB resources, auto-chunking, all six transform blocks, output, sinks, steps, plugin+segments, schemas |
| `proc` | processing |
| `dirs` | dirs |
| `batch` / `intake` / `unpack` / `produces` | batch caps / intake override / unpack stage / catalog product |
| **`parsing`, `grammarBlock`, `blockShaped`, `csv`, `frontend`** | **unified `parsing:` block** |

🔴 **Two traps in writing that analyzer, both of which produced a *false negative* — the dangerous
direction:**
1. `awk` has **no `\b` word boundary** (it means backspace). My first check for "does `csv` escape this
   section?" used `awk '/\bcsv\b/'`, matched nothing, and read exactly like "the section is clean". It
   cost an extraction that failed to compile on three symbols.
2. Stripping string literals with `"(?:[^"\\]|\\.)*"` **flips quote parity on a Java text block**
   (`"""…"""`) and swallows the real code after it. That hid *every* `b.*` read in the steps section,
   reporting its parameter list as `(raw)` when it is `(raw, b)`. **Strip text blocks first.**

The `leaks` half of the analyzer never strips strings, so it over-reports and a zero there is
trustworthy. The `reads` half must strip, so it is the half that can lie.

##### How tasks 2 and 5 got unblocked — the sequencing lesson

At `parse()` = 477 lines the four remaining tasks all looked leak-blocked, and 2+5 looked like a
"merge into one `parseGrammar`" that needed the `output:`/`sinks:` parsing moved out from between
them. **Both readings were improved by one cheap, un-listed task.**

Extracting `parseOutputAndSinks` (40 lines, both sections leak-free) was scheduled *purely as an
unblocker*. Once it landed, tasks 2 and 5 were separated by a single call line — and the better shape
became visible: rather than merge them and reorder that call, have `parseParsing` **return** the five
locals that cross the seam, as a private `Grammar` record. `parsePlugin` then takes the record (5
params, not 9) and unpacks it into locals of the original names, so its body — which carries the
asn1-vs-plugin refusal and the non-empty-segments rule — stays **byte-identical**.

⚠ I had written "⛔ do not split them behind a carrier record for internal plumbing" here, and then did
exactly that. The reversal is the point: with `output`/`sinks` still in between, a carrier would have
been plumbing around a problem. Once they were out, the record was the only option that **did not
reorder two validating sections** — and reordering changes which exception a doubly-invalid config
reports. A carrier for five values that genuinely must cross a seam is a name, not plumbing.

⭐ **The generalisable bit: when several tasks are blocked, look for the cheap un-listed task that is
*between* them.** It was not on the plan's list because the plan measured sections by size, and this
one is small. Its value was positional, not intrinsic.

##### Three ways this method's extractions went wrong, all silently

Every one of these compiled, and two produced identical behaviour — so the build was never the check.

1. **End marker matched inside an already-extracted method.** After a section moves, the *receiving*
   method also contains 8-space `// ── ` markers. A whole-file search for the next marker matched one
   of those and swept **638 lines**. Bound every marker search to `parse()`'s own line range.
2. **End marker was the next `// ── ` section, but a CALL line sat between.** Extracting
   `output`+`sinks` with the plugin marker as the boundary silently pulled `parseSteps(raw, b);` into
   `parseOutputAndSinks`, which then called it as its last statement. **Order was preserved, so every
   one of 3657 tests passed.** Only reading `parse()` back caught it.
3. **Anchoring 2+5 on `// ── schemas ─` swallowed output, sinks and steps** (277 lines) into a method
   named for grammar. Also compiled.

**The check that catches all three:** after each extraction, print `parse()`'s remaining `// ── ` markers
and `parseX(...)` calls and confirm the sequence still matches the original section order. That is one
grep, and it is the only thing that found #2.

Tasks 1–8 are **independently committable and can be done in any order** — they touch disjoint line
ranges. Order given is best-value-first so an interrupted arc still banks the wins.

**How to actually run these — three things learned doing task 1 (2026-08-27):**

1. ⚠ **The line numbers above drift after every extraction.** Task 1 removed 112 lines from `parse()`,
   so every later section moved. **Anchor on the `// ── <name> ──` marker text, never on a line
   number.** The markers are stable and unique; the numbers in the table are only a size estimate.
2. 🔴 **Move the body with a script, do not retype it.** These sections are dense with *pinned*
   fail-closed refusals and their justifying comments (the `rejects_table` bare-identifier refusal,
   the `compression` allow-list, the steps-vs-legacy exclusivity). A verbatim line-move cannot drift
   them; hand-copying 100+ lines can, silently, and the tests will not all catch it.
3. **There are two extraction shapes, and which one applies is a fact about the section, not a
   preference.** Decide it by asking where the section's output goes:
   - **value-producing** → `private static X parseX(...)` returning one record. Task 1 qualified: its
     16 `Builder` fields mapped exactly 1:1 onto the existing `Collector` record, so the 16 fields
     collapsed into one and `PipelineConfig`'s constructor lost a 5-line assembly block.
     ⭐ This is the shape worth looking for — it shrinks `Builder` too, not just `parse()`.
   - **builder-populating** → `private static void parseX(..., Builder b)`. Correct when the section
     feeds several unrelated destinations. Task 2 (`parsing:`) is this shape: 32 fields across the
     `CsvSettings` group *and* four independent frontends. Do **not** invent a carrier record to force
     the value-producing shape — that is a speculative abstraction, and CLAUDE.md §2 forbids it.

#### A — `ReadModel` role interfaces

| # | Task | Why this position |
|---|---|---|
| 1 | ~~Decide `ReadModel`'s package~~ — ✅ **DONE 2026-08-27: `com.gamma.service`** | Answered above, with the C3 premise refuted in the process. No longer blocks anything. |
| 2 | Add the interface + `CollectorService implements ReadModel` | ✅ **SHIPPED.** Zero-churn checkpoint: compiled first time, which independently confirmed the 10-member enumeration was exactly right. |
| 3–8 | Convert all six receivers: `ContextBroker`, `OperationalActions`, `DataSourceBundleResolver`, `MetricsService`, `ReportService`, `InspectoTools` | ✅ **SHIPPED.** ⚠ Re-ordered from the original, which led with `ReportService` "because it cuts a C3 edge" — it does not. Only the field/parameter type changed; `CollectorService.PipelineView` was left verbatim. |

##### What Phase A actually bought — measured, not argued

| Class | `CollectorService` refs after | What that means |
|---|---|---|
| **`ContextBroker`** | **0** | ⭐ The only genuine cross-package cut. It needed just `events()`, so nothing pulls the concrete class back in. |
| `OperationalActions` | 2 | import + `CollectorService.PipelineView` |
| `DataSourceBundleResolver` | 1 | `.PipelineView` (same package anyway) |
| `MetricsService` | 1 | `.PipelineView` (same package anyway) |
| `InspectoTools` | 2 | import + `.PipelineView` |
| **`ReportService`** | **3** | import + two `.PipelineView` loops — **and it still imports `EnrichmentService`** |

🔴 **`ReportService`'s import block is the receipt: `com.gamma.report` still imports `com.gamma.service`
three ways after the conversion.** The C3 `report → service` edge is exactly where it was. This is the
refutation from the top of Phase A, now demonstrated rather than argued — and it is what the plan
would have shipped as a "cycle shrink" had the premise gone unchecked.

**Honest scorecard for Phase A: one package edge cut (`intelligence.context → service`), five
signatures narrowed, and six collaborators that a test can now fake.** That is a real result. It is
not the coupling win the fan-out numbers implied.

⛔ Not in this sequence, deliberately: the `AssistAgent`/`IntelligenceAgent` SPI `init(...)` (needs
decision #1), `ApiContext`/`ControlApi`/`SpaceContext` (legitimately need the full surface), and the
three pure-passthrough classes (converting them buys only signature honesty).

#### D2b — the remaining giant methods — ⛔ CLOSED as LEAVE, 4 of 4 (grounded 2026-08-27)

The instruction here was "ground each first … expect at least one to be a LEAVE." Grounding returned
**four LEAVEs, and two of the four methods do not exist at anything like the cited size.**

| Method | Cited | **Actual** | Shape | Verdict |
|---|---|---|---|---|
| `PipelineEditable.lower()` | 336 | **336** ✅ | ~20 locals cross section boundaries; every block reads/writes the shared `out`/`collector`/`dirs`/`output`/`processing` maps, interleaved with strict/lenient branching | **LEAVE** — splitting needs an 8–15-arg list per piece or a mutable context object. That is restructuring, not extraction, and three pinned comments (`:594`, `:770`, `:838`) are glued to the ordering of *neighbouring* blocks. |
| `ConfigSpecs.pipeline()` | 280 | **280** ✅ | One flat `List.of(...)` of `FieldSpec` literals (~183 lines) then one of `CrossFieldRule` literals (~90), zero control flow outside self-contained predicate lambdas, ~0 crossing locals | **LEAVE** — a *declarative literal* gets longer and harder to read when chopped. Wrapping it adds two signatures and a `cores` thread-through so a reader must hold two scroll positions to reconstruct one `ConfigSpec`. |
| `FindingsSpec` ctor | 323 | **4** 🔴 | compact record ctor, two null-normalising lines | **DOES NOT EXIST** — the *whole file* is 369 lines. |
| `JobRoutes.maskSecrets()` | 256 | **10** 🔴 | one loop over a map | **DOES NOT EXIST** — the whole file is 422 lines. |

🔴 **The two over-reports were arithmetically impossible against their own files, and that is the
cheapest possible falsification: a 323-line method cannot live in a 369-line file.** Check a census
figure against `wc -l` of the file before believing it — that is one command, and it would have
killed both rows before anyone read a line of Java. This is the same detector that
[[d1-sweep-lessons]] recorded as over-reporting 3–6×; here it over-reported **30–80×**.

⚠ The two survivors are also a general lesson: **"long method" is not one shape.** `lower()` is long
because state is shared (extraction is impossible without restructuring); `ConfigSpecs.pipeline()` is
long because it is *data* (extraction is possible and makes it worse). Neither is the sequential-
sections shape D2a exploited. Ask which of the three a method is before scheduling it.

#### Closing task for the whole arc

When Track 1 is done and Track 2 is answered or abandoned: **distil the as-built facts into the OKF
concepts** (`okf/backend/modules/reactor.md` already has the cycle map; route/module structure →
`okf/backend/control-plane/control-api.md`), move anything still open to `BACKLOG.md`, then
`git mv` this plan to `archived-documents/plans-archive/` and update `INDEX.md` in the same commit.
The `handoff` skill checks this.

### Parallelism — what can run at once

Worktree-isolated agents make several of these concurrent, but the **main tree serialises on the
reactor gate** (two Maven builds on one tree produce phantom "package does not exist" failures).
Practical rule: any number of tasks may be *prepared* in parallel worktrees; only one may be
**gated and landed** at a time. D2a tasks 1–8 and Phase A tasks 3–8 are mutually independent and are
the natural candidates for parallel preparation.

### Explicitly NOT in the sequence

Phase B (`ObjectService`) and Phase F's broad split are **closed as grounded LEAVEs** — they are not
"later", they are decided. Re-opening either needs new evidence, not a free afternoon. Their
evidence is written up in their own sections precisely so that the next person does not re-derive
the same refuted premise from the same fan-out numbers.

## Operator decisions required

1. **Phase A / C3 — narrowing the `AssistAgent`/`IntelligenceAgent` SPI `init(CollectorService)`.**
   Excluded above because it "breaks third-party implementors". Cutting the last two edges of the C3
   cycle needs that narrowing.
   ⚠ **RE-SCOPED 2026-08-27 (second pass) — the cost recorded here is much larger than the real one,
   and the release-policy half of it is gone.** Measured against `v3.11.0`, the newest ancestor
   release:
   - `IntelligenceAgent` **does not exist there at all** — the whole `com.gamma.intelligence` package
     postdates 3.x. Narrowing it breaks nobody.
   - `AssistAgent` **did** ship (`@PublicApi(since = "3.0.0")`) — but its released method is
     `void init(SourceService service)`. Today it is `void init(CollectorService service)`. **The
     signature has ALREADY been changed since the last release**, by the Source→Collector rename taken <!-- vocab-allow: names the Source→Collector rename itself -->
     as a deliberate "breaking, NO version bump" (GLOSSARY §13). An implementor porting from 3.11.0
     must rewrite that method whatever we do, so narrowing it to `ReadModel` adds **zero** incremental
     break.
   ⛔ **The old framing — "a strictly bigger ask than #4" — does not survive this.** What remains is a
   genuine *design* question, and it is still the operator's:
   **Question: should the agent SPIs receive the narrow `ReadModel` instead of the full
   `CollectorService`?** (Payoff: `CollectorService` fan-in **16 → ~8**, against a floor of ~7.) It is
   no longer a question about the release policy.
2. **Phase C — how far to chase cycles.** C1 and C2 are worth cutting on the evidence. But if a cut
   turns out to need more than a relocation (i.e. it is a redesign), the honest default is to stop,
   record it, and leave the cycle documented. **Confirm that default.**
3. ✅ **ANSWERED 2026-08-27 — CUT IT. SHIPPED `15205362`.** The constant-ownership inversion: `TYPES`
   /`TIERS` canonical values moved to `pipeline.NodeAttribute`, `@PublicApi` `FindingsSpec` delegates
   (its signature is unchanged, so no break). ⛔ Do not re-ask.
   ⚠ **The question's own framing — "or leave the cycle recorded" — assumed a relocation alternative
   existed. It does not**: `AnnotationKinds`' consumers all live under `ops.*`, so the inversion was the
   only cut, not the tidier of two. Owner chosen as `pipeline` because `NodeAttribute` is the published
   node-type API the UI's `attribute-spec.ts` mirrors; a shared third home was rejected (two consumers
   do not justify one, and both candidate packages would blur a distinct vocabulary).
4. ✅ **ANSWERED 2026-08-27 — GRANTED, and subsequently found to have been UNNECESSARY.**
   `@PublicApi` types MAY relocate on a major bump — and separately, none of the types in question had
   ever been released, so no grant was owed. ⛔ **Do not re-ask, and do not treat a future `@PublicApi`
   relocation as gated without checking the tag first** (see Phase C step 2 and
   [`api-stability.md`](../okf/backend/control-plane/api-stability.md) §*Release baseline*).
   ⛔ Do not re-ask. The bump is **4.0.0**: master is `4.0.0-SNAPSHOT`. ⚠ **`v3.12.0` is not the last
   release on this line** — it is not an ancestor of `master`; the newest ancestor tag is `v3.11.0`.
   **Executed so far:** `PipelineView`/`PipelineRun`/`InboxStatus` promoted out of `CollectorService`
   (`7eab72d4`), fan-in 25 → 16. ⚠ **This is NOT the same decision as #1** — the note that said so was
   wrong. #1 breaks a `ServiceLoader` contract for third-party *implementors* (they must change code
   they own); #4 relocates types callers merely *reference*. Granting one does not grant the other,
   and #1 is still open.
   ⚠ The grant was given in the context of the record promotion, and the handoff asked that Track 2
   item 6 be scope-confirmed before building "a second API break". ✅ **That confirmation is moot:
   there is no API break to confirm.** C1/C2 relocate types absent from every release.
5. **Phase F carve-out — build it or not.** It is genuinely optional; the plan recommends *not*
   doing it unless the acquisition subtree is being worked on anyway.

## Scorecard — what grounding did to this plan

Worth recording, because it is the most reusable lesson here:

- **Phase 1: 5 of 7 slices** shrank or closed once grounded (S2 broad premise, S5 entirely,
  S6 ×2 arms, S7).
- **Phase 2: of the three options I recommended on fan-in/fan-out metrics, two were refuted**
  (`ObjectService`, `PipelineConfig`) and the third (`CollectorService` role interfaces) turned out
  to deliver a fan-*in* win rather than the fan-out win implied. Meanwhile the highest-value work —
  three undocumented package cycles — **was not in any of the three options**; the census found it.
- **The pattern: metrics point, code decides.** Size, fan-out and naming all generated plausible
  targets that reading the code refuted. Every phase above therefore keeps its own grounding gate,
  and a LEAVE with evidence is a successful outcome.

**Third round, 2026-08-27 — the plan's own TASK-level breakdown got the same treatment:**

- **D2b: 4 of 4 rows closed as LEAVE**, two of them because the method *does not exist* at the cited
  size (30–80× over-report, refutable by `wc -l` on the file).
- **D2a: 4 of 8 task verdicts were wrong.** "Self-contained" / "cohesive" / "one `Processing`-shaped
  result" were eyeball judgements about *state flow*, and eyeballs are bad at that. A 30-line script
  answering "which locals declared here are read later?" settled all 40 sections at once.
- **A: the question in task 1 was better than the reasoning behind it.** Asking "decide the package
  first" was right; *why* it mattered was wrong, and the real answer came from a nested record
  (`CollectorService.PipelineView`) that no fan-in/fan-out number could have surfaced.

⭐ **The generalisation, and it is the one worth carrying forward:** when a plan's row rests on a claim
about **structure** (does this state cross that seam? is that edge held by one import or three?), the
claim is checkable *mechanically and cheaply* — and eyeball judgements about structure were wrong
about half the time here. Write the ten-line script. But **falsify the script in both directions
first**: two of mine returned clean false negatives (an `awk \b`, a text-block quote-parity flip),
and a broken structural check reads *exactly* like a clean structure.
