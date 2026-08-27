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
79,963 → 80,240, and the god-node list is the *same ten classes with slightly higher degree*.
Phase 1 optimized file length, duplication, and per-file responsibility. None of those are
connectivity. This plan targets the structural complexity Phase 1 deliberately left alone.

## The reframe that drives every decision below

**Node degree is not complexity — fan-in and fan-out mean opposite things.**

| Class | fan-in | fan-out | verdict |
|---|---|---|---|
| `CollectorService` | 59 | **346** | knows about too much |
| `ControlApi` | 38 | **305** | router — fan-out is its job |
| `PipelineConfig` | 94 | **297** | one record carrying every concern |
| `ObjectService` | **25** | **179** | does a lot, few depend on it |
| `ApiContext` | 78 | 116 | healthy: reuse |
| `ComponentStore` | 55 | 90 | healthy |

- **High fan-in is reuse and we want it.** `PublicApi` (an annotation), `apiUrl()`,
  `apiErrorMessage()` are high-degree *because* one implementation serves many callers.
- ⚠ **Consolidation RAISES degree.** Phase 1 turned ~60 helper copies into single nodes with ~60
  inbound edges each. Optimizing for low degree would mean re-duplicating them. Duplication and
  connectivity pull in opposite directions — never trade one for the other blindly.
- **High fan-out is the real complexity.** A class naming 300+ distinct types is the one nobody can
  hold in their head.

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

**This was the phase I recommended first, on its fan-in/fan-out profile (179 out / 25 in). Reading
the code refuted it.** The numbers pointed at a class doing a lot that few depend on; the structure
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
fan-out of 346** — it still constructs and owns every field it always did. What it moves is
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

**The shape:** one cohesive `ReadModel` interface (~13 read-only accessors: `pipelines`, `configFor`,
`pathFor`, `statusStore`, `eventBus`/`eventLog`/`events`, `jobService`, `catalog`, `reports`,
`configSource`, `objects`, `dataRoot`, `enrichmentService`) — **not** five tiny interfaces, because
the consumers overlap heavily (`pipelines`/`configFor`/`statusStore` each appear in 4+) and none
needs fewer than 3–5 accessors.

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

**⚠ Interaction with Phase C3 — read before starting.** C3 is the `{report, intelligence.spi,
assist.spi, service}` cycle. Phase A cuts the **`report` edge only**; the two SPI edges survive
because narrowing the SPI is excluded above. So **C3 shrinks from four packages to three, it does not
dissolve.** For even that to work, `ReadModel` must live somewhere `service` can implement without
re-creating the cycle — putting it in `com.gamma.service` leaves `report → service` intact and buys
nothing structurally. **Decide `ReadModel`'s package before writing it**; that single choice
determines whether Phase A helps Phase C at all.

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

**STATUS 2026-08-27: step 1 SHIPPED; the cuts are BLOCKED on an operator decision.**

1. ✅ **`reactor.md` corrected** — a dated "Re-measured 2026-08-27" section supersedes (does not
   rewrite) the true-but-historical 2026-07-22 layering claim, with all three SCCs, their exact
   holding edges, and what is *not* a cycle (`catalog ↔ catalog.spi` is the deliberate SPI pair;
   `job → consignment` is legitimately one-way).
2. ⛔ **C1 and C2 cuts BLOCKED — every cycle-cutting class is `@PublicApi`.** Measured after the
   edge trace, which did not check the stability contract: `ConsignmentProcessJobType` and
   `ProcessorContext` are `@PublicApi(5.0.0)`, `AnnotationKinds` is `@PublicApi(4.9.0)`. Relocating
   any of them changes its FQN — a break that the stability policy permits **only on a major version
   bump**. For C2 there is no workaround: a deprecated alias at the old FQN would still sit in
   `consignment` and still reference `job`, so the cycle would survive it.
   - The one API-free path is **C1 via constant-ownership inversion** (move `TYPES`/`TIERS` canonical
     values to `pipeline`, `FindingsSpec` delegates, both edges then point `ops → pipeline`). That is
     a design change rather than a relocation, so per the operator's stated default it is documented,
     not built unasked. **This is now operator decision #4.**
3. C3: partly cuttable via Phase A — see there; its last two edges need decision #1.

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

**D2 — the giant methods.** `PipelineConfigParser.parse()` is **801 lines** — the largest method in
the backend by a wide margin. ⚠ Phase 1 already established the parser is *sequential section
parsing*, so the fix is NOT a dispatch table: it is extracting each `// ── section ──` block into a
named private method (`parseIntake`, `parseSinks`, …) that returns its piece. That is mechanical and
reviewable. Next: `PipelineEditable.lower()` (336), `FindingsSpec` ctor (323), `ConfigSpecs.pipeline()`
(280), `JobRoutes.maskSecrets()` (256).

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

### The governing principle for what remains

**Ask the decisions NOW, in parallel; do the undecidable-free work meanwhile.** The three blocked
items all wait on operator/product calls (a major-version window, an SPI compatibility stance) that
may take days and are not engineering questions. Serialising work behind them wastes the interval.
Nothing in the unblocked track depends on how they are answered.

### Track 1 — proceeds immediately, no decision needed

| # | Work | Why in this position |
|---|---|---|
| 1 | **D1 engine batch** *(in flight)* | Already running; finishes the sweep D1 started. Its fresh-sweep count also tells us whether the census's detector over-reports **systematically** — which governs how much any other census figure can be trusted. |
| 2 | **D2a — `PipelineConfigParser.parse()` (801 lines) → named section methods** | The single largest method in the backend, and pure extraction (the "sequential sections, not a dispatch chain" finding is already grounded, so the shape is known). Do it BEFORE any `PipelineConfig` work: a readable parser is the thing you need in hand if the F carve-out is ever approved. |
| 3 | **A — `ReadModel` + 6 collaborator conversions** | Independent of every blocked item, and the zero-churn path (`CollectorService implements ReadModel` first) makes each conversion a one-file commit. ⚠ **Decide `ReadModel`'s package as part of step 3, not later** — that choice is the only lever that lets Phase A also cut C3's `report` edge, and it cannot be revisited cheaply once six collaborators import it. |
| 4 | **D2b — the remaining giant methods** | `PipelineEditable.lower()` (336), `FindingsSpec` ctor (323), `ConfigSpecs.pipeline()` (280), `JobRoutes.maskSecrets()` (256). Lower value than D2a and entirely independent — correct place for the tail end of the arc, or to drop if attention is needed elsewhere. |

**Why A comes after D2a rather than before:** both are safe, but D2a is *pure* extraction with a known
shape, while A introduces a new published-ish type whose package placement has a consequence
(C3). Doing the mechanical one first keeps the arc's momentum while leaving the judgment call for
when there is time to make it properly.

### Track 2 — blocked; ask now, build when answered

| # | Work | Waits on | If the answer is no |
|---|---|---|---|
| 5 | **C1 cut via constant-ownership inversion** | decision #3 | Cycle stays documented in `reactor.md`. No further cost. |
| 6 | **C1 + C2 cuts via relocation** | decision #4 (major-version window) | Both cycles stay documented. This is the only path for C2 — it has no API-free alternative. |
| 7 | **C3's last two edges** | decision #1 (SPI narrowing) | C3 shrinks 4 packages → 3 via Phase A and stops there. |
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

| # | Task | Lines | Why this position |
|---|---|---|---|
| 1 | extract `parseCollector(...)` | `:750-869` (~119, 9 nested sub-blocks) | Biggest single win and the cleanest: the sub-blocks already map 1:1 onto the existing `Collector` record, so it returns one value. ⭐ Also de-risks the F carve-out, whose safe increment is exactly this subtree. |
| 2 | extract `parseParsing(...)` (csv settings + unified `parsing:`) | `:340-448` (~108) | Second-largest, self-contained, feeds `CsvSettings`. |
| 3 | extract `parseSchemas(...)` | `:618-701` (~83) | Cohesive, produces `Schemas`. |
| 4 | extract `parseSteps(...)` | `:488-563` (~75) | ⚠ Carries the **pinned** steps-vs-legacy exclusivity refusal and the list-arity refusal — move them VERBATIM with their comments, and keep `resolveSteps`'s single choke point intact. |
| 5 | extract `parsePluginAndSegments(...)` | `:563-618` (~55) | Cohesive. |
| 6 | extract `parseProcessing(...)` (processing, batch caps, streaming, DuckDB, chunking, intake) | `:192-258` (~66) | Six adjacent markers, one `Processing`-shaped result. |
| 7 | extract `parseDirs(...)` (dirs + audit/manifest paths) | `:148-192` (~44) | Produces `Dirs`. |
| 8 | extract `parseTransformBlocks(...)` (duplicate check, dedup, summarize, join, map, route) | `:279-340` (~61) | ⚠ These are the blocks `prepare()`'s fail-closed arming reads **in combination** — extract the *parsing*, never the arming logic. |
| 9 | **STOP and reassess** | — | After 1–8 `parse()` should be ~250 lines. Judge whether the identity/gates head (`:74-148`) is worth touching: those set many independent top-level locals, so extraction may just move noise. **A grounded "leave the head" is a valid end.** |

Tasks 1–8 are **independently committable and can be done in any order** — they touch disjoint line
ranges. Order given is best-value-first so an interrupted arc still banks the wins.

#### A — `ReadModel` role interfaces

| # | Task | Why this position |
|---|---|---|
| 1 | **Decide `ReadModel`'s package** and record the reasoning | ⚠ MUST be first. It is the only lever that lets A also cut C3's `report` edge, and six importers make it expensive to revisit. Putting it in `com.gamma.service` buys nothing structurally. |
| 2 | Add the interface + `CollectorService implements ReadModel` | Zero-churn checkpoint: green, no behavior change, no test edits. Everything after is reversible from here. |
| 3–8 | Convert one receiver per commit: `ReportService` → `MetricsService` → `DataSourceBundleResolver` → `ContextBroker` → `InspectoTools` → `OperationalActions` | `ReportService` first — it is the one whose conversion actually cuts a C3 edge. `ContextBroker` needs only `events()`, so it is the smallest sanity check if something looks wrong. |

⛔ Not in this sequence, deliberately: the `AssistAgent`/`IntelligenceAgent` SPI `init(...)` (needs
decision #1), `ApiContext`/`ControlApi`/`SpaceContext` (legitimately need the full surface), and the
three pure-passthrough classes (converting them buys only signature honesty).

#### D2b — the remaining giant methods

One commit each, in descending value, and **each independently droppable**:
`PipelineEditable.lower()` (336) → `FindingsSpec` ctor (323) → `ConfigSpecs.pipeline()` (280) →
`JobRoutes.maskSecrets()` (256). ⚠ Ground each first — D2a's premise (sequential sections) came from
grounding, and these four have not been read yet. Expect at least one to be a LEAVE.

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
   Excluded above because it breaks third-party implementors. Cutting the last two edges of the C3
   cycle needs that break, so C3 can only be *shrunk* (4 packages → 3) without a decision here.
   **Question: is the SPI signature frozen, or may a major version narrow it?**
2. **Phase C — how far to chase cycles.** C1 and C2 are worth cutting on the evidence. But if a cut
   turns out to need more than a relocation (i.e. it is a redesign), the honest default is to stop,
   record it, and leave the cycle documented. **Confirm that default.**
3. **Phase C1 — the constant-ownership inversion.** C1 can be cut without any API break by moving
   the `TYPES`/`TIERS` canonical values to `pipeline` and having `@PublicApi` `FindingsSpec` delegate
   (its signature is unchanged, so no break). This is a design change, not a relocation, so it is
   documented rather than built. **Question: cut C1 this way, or leave the cycle recorded?**
4. **Phases C1/C2 by API break — may `@PublicApi` types relocate on a major bump?** Both cycles are
   held by published classes. If a 6.0.0 is on the horizon, both become straightforward relocations;
   if not, they stay documented. **This is the same decision as #1, and it governs most of Phase C.**
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
