<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-01 (Stage 3 of la-separation-execution-plan.md). DESIGN ONLY — nothing is built. DECISIONS 1–5 SIGNED 2026-10-01
  (operator: "go with your recommendations"); no step is started by this.
  Retire per the three-tier lifecycle in CLAUDE.md when D-1 ships (or is declined).
-->

# LA separation — D-1 design (extract the shared platform layer)

Option D's phase **D-1** ([`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.8): give Link
Analysis (LA) a thin shared platform to stand on, so it no longer needs `inspecto-processor` (the control plane),
`inspecto-etl` or `inspecto-engine`. This file is the design the operator signs before any extraction.
⛔ Grounding method: transitive import closures computed over `src/main/java` on 2026-10-01 (scripts in the
session scratchpad, not committed). When this and the code disagree, re-ground; never trust the plan.

## 1. What the grounding changed

The feasibility plan sized D-1 as **L**. The closures say it is **M**, because the real coupling is far narrower
than the import counts suggested:

| Finding (2026-10-01) | Consequence |
|---|---|
| The HTTP + auth contract (`ApiContext` `ApiException` `ErrorCodes` `RouteModule` `Subject` `RowScope` `ComponentAccess` `WriteGates` `EntityTypes`) closes over **46 classes** once `ApiContext`'s host hooks are cut: 23 in `inspecto` (the 9 seeds plus `AccessDecider(s)` `AccessPolicies` `AuditTrail` `Authenticator(s)` `CapabilityManifest` `Envelope` `GeoCountryResolver(s)` `Handler` `Idempotency` `Roles` `SpiSlot`), 11 in `inspecto-audit-spi` (already extracted), 7 in `inspecto-util`, 4 in `inspecto-config`, 1 in `inspecto-api`. With the hooks left in, the same closure is **503 classes** | The whole problem is one interface: `ApiContext` is the **only** class in the cluster that touches the host (`CollectorService`, `SpaceManager`, `SseStreams`) |
| LA uses those hooks **five times**: `service().objects()` ×4 (Cases) and `service().alertService()` ×1 | Both are bridge jobs (Cases, Alert Rules); `la-core` never needs them |
| `inspecto-event` → `inspecto-etl` is **one edge**: `ParquetEventStore` → `PartitionWriter`. The audit trio `Event` `EventLog` `EventType` closed over 11 classes in `inspecto-event` plus 3 util with no ETL (`EventLog` ↔ `MetricRegistry` call each other, so both moved — shipped as step 2, `09a94ab56`) | The audit SPI is a move, not a rewrite — and `inspecto-event` can keep `ParquetEventStore` untouched |
| `EventType` is a `final class` of constants (not an enum) and nothing outside `inspecto-event` calls `values()` / `valueOf()`; **59 `LINK_*` constants** live in core | LA's event types can leave core with no behaviour change |
| `DatasetRead` closes over **54 classes** (engine 21, audit-spi 11, util 10, config 7, sql 4, api 1); `QueryExecutor` over 10, one of them in `inspecto-etl` (`DuckDbExtension`) | The Dataset-read port is already extracted (SEP-01) but is a real slice of the engine, not a small one; the bridge implements it and LA never imports it |
| `Authenticator` closes over **23 classes** with the host hooks cut (493 with them in); `OidcAuthenticator` over **26** (15 in `inspecto`) | Moving OIDC (D12) is cheap once the contract is out — it adds only `AccessGrants` and the `inspecto-security` classes |
| 🔴 **The HTTP half and the auth half are mutually dependent.** `ApiContext` references `Authenticator`, `ComponentAccess`, `Roles` and `Subject`; `Subject`, `Roles`, `ComponentAccess`, `RowScope`, `AccessDecider`, `AccessPolicies` and `AuditTrail` reference `ApiContext` back | Two modules `inspecto-http-spi` + `inspecto-auth-spi` are **impossible** (a Maven cycle). The contract must be ONE module (Decision 6) or `ApiContext` must stop naming auth types, which is a larger redesign |
| `PendingChanges` (LA uses it in one file) is a **host** class: it references `ControlApi`, `JobRoutes`, `ApprovalPolicy`, `AccessGrants` and `ContentHash`. `AnnotationTargets` references engine classes plus `RowScope` | Neither can move into the SPI; LA's use of both goes behind a port in the bridge |
| The **graph algorithms** (`GraphAlgorithms`, `GraphPaths`, `GraphStructure`, `GraphCentrality`, `GraphIterative`, `GraphSuspicion`) import **nothing** from the platform — plain JDK | `la-graph` can be carved out **first**, before any SPI exists |
| Outside `inspecto`, the contract is imported by `inspecto-geo-link` (most), `-ops`, `-events`, `-exchange`, `-metrics`, plus `Subject` / `ComponentAccess` in `-policy`, `-security`, `-demo-auth` | Every optional module is touched by the `ApiContext` split; the edit per file is one import or one cast |

## 2. Target modules

```
 shared (extracted)         inspecto-audit-spi   Event · EventLog · EventType            (+ util: CurrentSpace, JsonAttributes, Values)
                            inspecto-auth-spi    LOWER layer — RequestAttrs · WriteRootProvider · ApiException · ErrorCodes · SpiSlot · WriteGates · Subject · Roles · ComponentAccess ·
                                                 RowScope · AccessDecider(s) · AccessPolicies · AuditTrail · AccessGrants · Authenticator(s) · CapabilityManifest · GeoCountryResolver(s)
                            inspecto-http-spi    UPPER layer, depends on auth-spi — ApiContext (extends WriteRootProvider) · RouteModule · Handler · Envelope · Idempotency
                            (existing, unchanged) inspecto-api · -util · -config · -sql
 LA (new, LA-only deps)     la-graph             the six Graph* classes — no dependency but the JDK
                            la-core              model, Investigation log + evaluator, pattern engine, Snapshot/Fact stores, EntityTypes, LinkAnalysisSettings, LA event-type constants
                            la-api               routes over http-spi/auth-spi + the module-owned OpenAPI fragment
 bridge                     la-inspecto          RouteModule adapter · HostContext access · the three ports below · Alert Rules · Cases · Pipeline node type
```

**Ports (LA-owned interfaces, implemented only in the bridge).** `DatasetProvider` (grown from `DatasetRead`:
registry read, relation SQL, query), `CasePort` (the four `service().objects()` uses), `AlertPort` (the one
`service().alertService()` use). A port with no implementation makes its feature report itself **absent**, never
half-working (feasibility plan §7.3).

**How the cycle is broken (Decision 6 = break it, two modules; mechanism built and verified 2026-10-01).** The auth classes used
`ApiContext` only through its **static** request-attribute helpers, never an instance, and `WriteGates.requireWriteRoot` needed only
`api.writeRoot()`. So: a new `RequestAttrs` (the 15 `ATTR_*` names, `REQUEST_SCOPES`, `attr`, `dropAttrScope`, `correlationId`, `subject`,
`actor`, `actorType`, `ip`, `userAgent`, `HEADER_AGENT_SESSION`) and a one-method `WriteRootProvider` sit in the lower layer;
`ApiContext` extends `WriteRootProvider` and keeps one-line aliases and delegates, so no call site changed. Dependency graph afterwards:
lower → upper edges **none**; the lower set references no other `inspecto` class; the upper set touches the host only through
`CollectorService`, `SpaceManager` and `SseStreams` (what `HostContext` takes over). Direction is **`inspecto-http-spi` → `inspecto-auth-spi`**.

**The `ApiContext` split.** `ApiContext` keeps routing (`get/post/put/patch/delete/hasRoute/stub`), the request
helpers (`body`, `rawBody`, `replay`, `actor`, `ip`, `query`, `param`, `paged`), the response helpers and the capability
gates; it **loses** `service()`, `spaces()` and `sseStreams()`. A new `HostContext extends ApiContext` in
`inspecto-processor` carries those three plus `writeRoot()` / `dataRoot()` as the host defines them, and a static
`HostContext.of(ApiContext)` returns it or fails loudly. `ControlApi` already passes one object, so nothing is
re-plumbed; the optional modules that need host services (`-ops`, `-exchange`, `-events`, `-metrics`) switch one
import and one call.

**Dependency rule, enforced.** `la-graph`, `la-core` and `la-api` must not depend, directly or transitively, on
`inspecto-processor`, `inspecto-etl`, `inspecto-engine` or `inspecto-acquire`. Only `la-inspecto` may. Enforce it with a
`maven-enforcer` `bannedDependencies` rule per module **plus** a dependency-tree test run by `tools/` (the repo's habit:
a derived guard, not a hand-kept list; mutation-check it by adding a banned dependency and watching it go red).

## 3. Extraction order — each step compiles, passes its unit tests and ships alone

| Step | Work | Proof |
|---|---|---|
| **1** | **`la-graph`.** Move the six `Graph*` classes and their five parity tests (the fixtures stay where the TS specs read them) into a new module | The parity classes green from the new module; `mvn dependency:tree` for it shows the JDK only; `inspecto-geo-link` depends on it |
| **2** | **`inspecto-audit-spi`.** Move `Event` `EventLog` `EventType` (+ the 59 `LINK_*` constants stay for now); `inspecto-event` and `inspecto-processor` depend on it; `ParquetEventStore` stays in `inspecto-event` | `inspecto-audit-spi`'s dependency tree has no `inspecto-etl`; `inspecto-event` and the audit tests green |
| **3** | ✅ **BUILT 2026-10-01 as two modules, `inspecto-http-spi` → `inspecto-auth-spi` (Decision 6).** Plan as written was: Split `ApiContext`; add `HostContext`; move the ~22 classes (the 23 in `inspecto` minus `EntityTypes`, which is LA's); fix the ~40 importing files in `-ops` `-events` `-exchange` `-metrics` `-policy` `-security` `-demo-auth` `-geo-link`. **Keep the package name `com.gamma.control`** (Decision 2) | The affected modules' unit tests; `openapi-v1.json` unchanged (a route table must not move); `AbsentGeoLinkRoutes` parity test green |
| **4** | ✅ **BUILT 2026-10-01.** `inspecto-security` no longer depends on `inspecto-processor` at all (test scope only — one test boots a real `CollectorService`). `OidcAuthenticator` alone closed over auth-spi classes; the OTHER four classes of the module needed four more small, host-free types, so they moved into `inspecto-auth-spi` too (packages unchanged, Decision 2): `TokenRelay` (from `inspecto`), `EgressPolicy` (from `inspecto-engine`, plain JDK), `SecretResolver` + `SecretsProvider` (from `inspecto-acquire`); `inspecto-acquire` now depends on auth-spi. `AccessGrants` was already in auth-spi. Planned as: `OidcAuthenticator` depends on `inspecto-auth-spi`, not `inspecto-processor` (ground `AccessGrants` first — it is not in the 20-class closure) | `inspecto-security` tests with a Subject attached; ⚠ a `Roles.SEED` change needs `inspecto-security` tests (project gotcha) |
| **5** | ✅ **BUILT 2026-10-01 (5a `7180a18c3` entity store; 5b with step 6, see "As built" below).** **`la-core` / `la-api` out of `inspecto-geo-link`.** What remains in `inspecto-geo-link` is the bridge. Move `EntityTypes`, `LinkAnalysisSettings` and the LA event-type constants into `la-core` | Dependency rule green (mutation-checked); `ControlApiInv*` real-HTTP tests green |
| **6** | ✅ **BUILT 2026-10-01, together with 5b — with two ports, not three (see "As built").** **Ports + bridge.** Introduce `DatasetProvider`, `CasePort`, `AlertPort`; `la-inspecto` implements them from `DatasetRead`, `ObjectAccess`, `AlertService`; `AnnotationTargets`, `PendingChanges` and the Alert-Rule-bound Investigation code move behind it | LA real-HTTP tests green with all three ports bound **and** with each port unbound (feature reports absent, 503/capability off — never a stack trace) |
| **7** | **Reactor gate.** One full `mvn -o clean test -Pedition-enterprise` plus UI lint/test/build, in a clean worktree at HEAD | Operator-asked GAUNTLET; not once per step |

> **As built — steps 5b + 6 (2026-10-01, one lane, three commits).** Link Analysis stands on the platform without the core.
> `inspecto-la-core` (package `com.gamma.la.core`): `AdmiraltyGrade` `BranchingPatternEngine` `InvestigationEvaluator` `InvestigationTime`
> `LinkIds` `PatternQueryCompiler` `SnapshotStore` + the ports `DatasetProvider` / `DatasetProviders` and `CasePort` / `CasePorts`.
> `inspecto-la-api` (`com.gamma.la.api`): `GeoRoutes` `InvRoutes` `PatternRoutes` `ValueMeasureRoutes` `InvestigationRoutes` `DossierRoutes`
> `WorkingSetRoutes` `InvestigationTemplateRoutes` `InvestigationCoverageRoutes` `InvestigationCaseRoutes` `EntityIdentityRoutes` +
> `EntityMasking` `GraphDossierBuilder` `ValueMeasures`. `inspecto-geo-link` (`com.gamma.geolink`) is the **bridge**: `EngineDatasetProvider`,
> `HostCasePort`, and the Alert-Rule-bound `InvestigationMeasureRoutes` + `WorkingSetMeasures`; artifactId and bundle name unchanged.
> Routes, URLs and `openapi-v1.json` are unchanged. Both new modules are in the four edition profiles, staged as optional jars
> (`tools/bundle-modules.mjs`, `package.ps1`), and governed: `tools/check-module-deps.mjs` + a matching enforcer rule in each pom,
> mutation-checked (adding `inspecto-engine` to `inspecto-la-api` turns BOTH layers red).
>
> **What changed from the plan, and why.**
> * **Two ports, not three.** `AlertPort` was not built: the Alert-Rule code (`InvestigationMeasureRoutes`, `WorkingSetMeasures` — it implements the
>   ENGINE SPI `InvestigationMeasureProbe`) is bridge code by nature and stays in `inspecto-geo-link`, which depends on `la-api`/`la-core` (the correct
>   direction). Nothing in `la-*` calls the Alert service, so there is nothing to put behind a port.
> * **`InvestigationCaseRoutes` moved to `la-api` whole**, not split. The host-bound part is exactly two reads — the Case summary and "may the caller
>   see it" — so `CasePort` is `available(api)` / `summary(api, ref)` / `visibleTo(ex, summary)` and the Case rules (kind, closed, owner/assignee) stay
>   with the routes. Unbound or without ops installed = the existing "ops not installed" behaviour (a link is stored unverified and grants nothing).
> * **`DatasetProvider`** = `dataset` / `datasets` / `relationSql` / `run` ×3 / `runPlanned` / `predicate`, with LA-owned `Request` `Result` `Column` `Sort`
>   `Entry` `Planner` records. Unbound = `503 CAPABILITY_UNAVAILABLE` from `DatasetProviders.require()`. `SpiSlot` was made `public` (class, constructors,
>   `active`, `forTest`) so the ports can use it; both `*Ports` gained `forTestAbsent` so the bridge's own tests can prove the unbound path with the bridge on the classpath.
> * **`GraphDossierBuilder`, `ValueMeasures`, `EntityMasking` live in `la-api`, not `la-core`:** each calls static helpers of a route class
>   (`InvestigationRoutes.listClause`…, `InvRoutes.relationColumns`) and `EntityMasking` is route-side masking. Pulling those helpers down would have been a
>   refactor of 1900-line `InvestigationRoutes`; the dependency rule is satisfied either way.
> * **`EntityListRoutes`' host-free statics** (`read` `append` `reason` `type` `masked` `LIST_ID`) moved to `inspecto-entity-store` as `EntityListFacts`, because
>   LA may not depend on `inspecto-entity-list`.
> * **Allowlists.** `la-core`: api, util, config, audit-spi, auth-spi, **http-spi** (reached through `entity-store` whether named or not), entity-store, **sql**
>   (`SqlGuard` / `SqlSandboxPolicy` — host-free: it reaches only api, config, util). `la-api` adds `la-core`.
> * **Not done:** Decision 3's move of the 59 `LINK_*` event-type constants out of `inspecto-audit-spi` (still owed; nothing outside `la-*` / the bridge uses them).
>   `PendingChanges` stays host-side with the Alert Rule route.
> * **Tests.** `LaApiPortsTest` (6, a proxy `ApiContext` + a fake `DatasetProvider`, asserts the engine is not on the classpath), `ControlApiLaPortsBridgeTest` (4:
>   ServiceLoader binding, adapter contract against `DatasetRead`/`QueryExecutor`/`ConditionSql`, each port unbound over real HTTP). The 261 pre-existing tests of
>   the old module are all present: 252 in the bridge, 5 in `la-core` (`LinkIdsTest` `SnapshotStoreTest`), 4 in `la-api` (`InvRoutesTraversalPolicyTest`).

Steps 1 and 2 are independent and can run in parallel lanes; 3 precedes 4–6; 5 and 6 can split by file set.
**Size:** step 1 S · 2 S · 3 M · 4 S · 5 M · 6 M. Total **M–L**, down from the feasibility plan's L.

> **Correction (2026-10-01, step-3 re-grounding).** The first version of §1 used a closure script whose comment stripper
> treated a `/*` inside a string literal as the start of a block comment, so it silently dropped real references: it said
> 20 / 248 / 17 / 4 classes where the corrected figures are 46 / 503 / 54 / 23. The conclusions that survive are the central ones —
> `ApiContext` is the single host-touching class, and the LA ↔ host coupling is five calls — but the layout changed:
> the HTTP and auth contracts are cyclic, so §2's two SPI modules become one (Decision 6). Re-ground with a string-aware
> tokenizer, never a regex over raw source.
>
> **Second blind spot, found by the compiler while building step 3.** The closure tool resolves `import` lines and same-package
> simple names only, so a *fully-qualified inline reference* (`com.gamma.service.OptionalSpi.first(…)`, `com.gamma.pipeline.ComponentStore`)
> is invisible to it. Four such references sat in the "closed" lower layer: `SpiSlot` → `OptionalSpi` (moved down with it — plain
> JDK + slf4j) and `WriteGates`' config-registry checks → `ImportPaths` / `ComponentStore` / `ComponentRegistry` (split out into the
> package-private `ConfigTargetGuard` in `inspecto`, used only by the three `/config/*` route classes). **A closure is a
> prediction; the compile of a clean checkout (`mvn clean test-compile`, never an incremental one) is the proof.**

## 4. Risks

- **Split package.** Keeping `com.gamma.control` across `inspecto-http-spi`, `inspecto-auth-spi` and `inspecto-processor` works on the
  classpath (the bundle is classpath-based; `inspecto-geo-link` tests already split it) but forbids JPMS modules and is a
  trap for any future `module-info`. A rename is mechanical later. See Decision 2.
- **Hand-kept mirrors multiply.** A new module adds a place where `AbsentGeoLinkRoutes.SURFACE`, `CapabilityManifest` and
  the bundle module lists must agree. SEP-02 stays deferred, so add each new module to `tools/bundle-modules.mjs` and the
  static profile list in `pom.xml` in the **same** commit and run the editions guards.
- **Entity Lists.** Decision D7 (signed) moves Entity Lists to core before assurance WS-12; `EntityTypes` is imported by 9
  `inspecto-geo-link` files and by no one else. Step 5 puts `EntityTypes` in `la-core`; if D7 lands first the Entity-List
  classes belong in a core module that depends on `inspecto-http-spi` and `inspecto-auth-spi`, not in `la-core`. Order this
  with SEP-08 before step 5 (Decision 4).
- **Behaviour must not change for Inspecto.** Every step is a move; the only new behaviour is "port unbound ⇒ feature
  absent", and it is exercised by the step-6 proof.

## 5. Decisions owed (operator)

⛔ None may be answered by an implementer in passing. Write the answer on the **Answer** line.

**Decision 1 — Shape of the `ApiContext` split.** (a) `ApiContext` (SPI) + `HostContext extends ApiContext` in the core,
`HostContext.of(ctx)` for modules that need host services; (b) a typed-port lookup `ctx.port(Class)` on `ApiContext`
for everything host-side, including for the non-LA modules.
*Recommendation:* (a) for the platform and the non-LA modules (mechanical, one line per file), and LA-owned ports (§2)
only for LA's three real needs — (b) for all modules is a wider rewrite with no second customer.
**Answer:** (a) for the platform and the non-LA modules (`ApiContext` SPI + `HostContext` in the core, `HostContext.of(ctx)`), and LA-owned ports (§2) only for LA's three real needs. — operator 2026-10-01

**Decision 2 — Package names for moved classes.** Keep `com.gamma.control` (and `com.gamma.event`) so D-1 is a pure move with no
import churn, or rename now to `com.gamma.spi.*`?
*Recommendation:* keep for D-1; rename in a separate mechanical change if JPMS or a clean public surface is wanted.
**Answer:** keep `com.gamma.control` / `com.gamma.event` for D-1; rename in a separate mechanical change if JPMS or a clean public surface is wanted. — operator 2026-10-01

**Decision 3 — Where LA's event-type constants live.** Move the 59 `LINK_*` constants out of core into `la-core` in step 5
(requires `EventType` to accept module-defined types, or LA to define its own constants class), or leave them in
`inspecto-audit-spi` for D-1 and move them at D-5?
*Recommendation:* leave them for D-1 (step 2 stays a pure move); move them in step 5 once `la-core` exists, since nothing
outside `inspecto-event` enumerates the type.
**Answer:** leave the 59 `LINK_*` constants in `inspecto-audit-spi` for D-1 (step 2 stays a pure move); move them in step 5 once `la-core` exists. — operator 2026-10-01

**Decision 4 — Order against Entity Lists (D7 / SEP-08).** Do SEP-08 (Entity List routes to core) **before** step 5, or
let `la-core` own them in D-1 and move them later?
*Recommendation:* SEP-08 first — one move now beats two, and assurance WS-12 needs Entity Lists without LA.
**Answer:** SEP-08 first (Entity List routes to core before step 5). — operator 2026-10-01

**Decision 5 — Dependency guard form.** `maven-enforcer` banned-dependency rules only, or those **plus** a dependency-tree
test under `tools/`?
*Recommendation:* both — the enforcer fails fast at build time, the tool test is mutation-checkable in CI like the repo's
other derived guards.
**Answer:** both — `maven-enforcer` banned-dependency rules plus a dependency-tree test under `tools/`, mutation-checked. — operator 2026-10-01
*Built 2026-10-01 (for the five modules already extracted — `inspecto-la-graph`, `-audit-spi`, `-auth-spi`, `-http-spi`, `-security`):* `tools/check-module-deps.mjs` holds the policy table `ALLOWED` (module → the `inspecto-*` modules it may reach, test scope exempt), derives the real reach from the poms and fails on a reach outside the allowlist, a missing enforcer rule, or an enforcer allowlist that drifted from the table; each module's pom carries the matching `bannedDependencies` rule (excludes `com.gamma.inspector:*` at compile/provided/runtime, includes only the allowlist). Falsified on both layers: a compile `inspecto-engine` dependency in `http-spi`, and `inspecto-processor` promoted from test to provided in `security`, each turned the Node guard AND the enforcer red; `tools/check-module-deps.test.mjs` pins nine cases. **Maintenance rule: every module this plan extracts from now on (`inspecto-entity-list` is not governed — it is a feature module; `la-core`, `la-api` are) must be added to `ALLOWED` and given the same enforcer rule in the commit that creates it.**

**Decision 6 — One module for the HTTP + auth contract (supersedes the two-module layout in §2).** The grounding in §1 shows
`ApiContext` and the auth types depend on each other, so they cannot be separate Maven modules. Options: (a) one module named
for what it holds — `inspecto-control-spi` (package `com.gamma.control` is kept, Decision 2); (b) one module named
`inspecto-http-spi`; (c) break the cycle by making `ApiContext` independent of `Subject` / `Roles` / `ComponentAccess` /
`Authenticator` (a redesign of the request pipeline — not recommended for D-1).
*Recommendation:* (a) — the name matches the package it keeps and it holds the whole 22-class contract; `inspecto-audit-spi`
already sits beside it as the other narrow module (D8).
**Answer:** (c) break the cycle and keep two modules — operator 2026-10-01 (not the recommendation). Built as `RequestAttrs` + `WriteRootProvider`
in the lower layer (see §2); 462 tests green across the 50 test classes that touch these types.

## 6. References

[`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.3 (target architecture), §7.8 (phases), §7.11 and
§7.12 (the grounded edges this design starts from) · [`la-separation-execution-plan.md`](la-separation-execution-plan.md)
Stage 3 · [`../okf/backend/editions/editions-model.md`](../okf/backend/editions/editions-model.md) (the static profile
module list that must stay in step).
