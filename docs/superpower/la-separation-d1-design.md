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
| The HTTP + auth contract (`ApiContext` `ApiException` `ErrorCodes` `RouteModule` `Subject` `RowScope` `ComponentAccess` `WriteGates` `EntityTypes`) closes over **20 classes** once `ApiContext`'s host hooks are cut: 14 in `inspecto`, 4 in `inspecto-util`, 1 each in `inspecto-config` / `inspecto-api`. With the hooks left in, the same closure is **248 classes** across nine modules | The whole problem is one interface. `ApiContext.service()` (`CollectorService`) and `ApiContext.spaces()` (`SpaceManager`) are the **only** edges from the contract into the core |
| LA uses those hooks **five times**: `service().objects()` ×4 (Cases) and `service().alertService()` ×1 | Both are bridge jobs (Cases, Alert Rules); `la-core` never needs them |
| `inspecto-event` → `inspecto-etl` is **one edge**: `ParquetEventStore` → `PartitionWriter`. The audit trio `Event` `EventLog` `EventType` closes over **13 classes** (10 event + 3 util) with no ETL | The audit SPI is a move, not a rewrite — and `inspecto-event` can keep `ParquetEventStore` untouched |
| `EventType` is a `final class` of constants (not an enum) and nothing outside `inspecto-event` calls `values()` / `valueOf()`; **59 `LINK_*` constants** live in core | LA's event types can leave core with no behaviour change |
| `DatasetRead` closes over **17 classes** (sql 2, engine 7, util 3, config 4, api 1); `QueryExecutor` over 9, one of them in `inspecto-etl` (`DuckDbExtension`) | The Dataset-read port is already small and already extracted (SEP-01); the bridge implements it |
| `Authenticator` closes over **4 classes** (itself, `Subject`, `ApiContext`, `PublicApi`); `OidcAuthenticator` adds `AccessGrants` and `Roles` | Moving OIDC (D12) is cheap once the auth contract is out |
| The **graph algorithms** (`GraphAlgorithms`, `GraphPaths`, `GraphStructure`, `GraphCentrality`, `GraphIterative`, `GraphSuspicion`) import **nothing** from the platform — plain JDK | `la-graph` can be carved out **first**, before any SPI exists |
| Outside `inspecto`, the contract is imported by `inspecto-geo-link` (most), `-ops`, `-events`, `-exchange`, `-metrics`, plus `Subject` / `ComponentAccess` in `-policy`, `-security`, `-demo-auth` | Every optional module is touched by the `ApiContext` split; the edit per file is one import or one cast |

## 2. Target modules

```
 shared (extracted)         inspecto-audit-spi   Event · EventLog · EventType            (+ util: CurrentSpace, JsonAttributes, Values)
                            inspecto-http-spi    ApiContext (HTTP half) · ApiException · ErrorCodes · RouteModule · SpiSlot · WriteGates
                            inspecto-auth-spi    Subject · RowScope · ComponentAccess · Authenticator · AccessDecider(s) · AccessPolicies · Roles
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
| **3** | **`inspecto-http-spi` + `inspecto-auth-spi`.** Split `ApiContext`; add `HostContext`; move the 14 classes; fix the ~40 importing files in `-ops` `-events` `-exchange` `-metrics` `-policy` `-security` `-demo-auth` `-geo-link`. **Keep the package name `com.gamma.control`** (Decision 2) | The affected modules' unit tests; `openapi-v1.json` unchanged (a route table must not move); `AbsentGeoLinkRoutes` parity test green |
| **4** | **Move OIDC.** `OidcAuthenticator` depends on `inspecto-auth-spi`, not `inspecto-processor` (ground `AccessGrants` first — it is not in the 20-class closure) | `inspecto-security` tests with a Subject attached; ⚠ a `Roles.SEED` change needs `inspecto-security` tests (project gotcha) |
| **5** | **`la-core` / `la-api` out of `inspecto-geo-link`.** What remains in `inspecto-geo-link` is the bridge. Move `EntityTypes`, `LinkAnalysisSettings` and the LA event-type constants into `la-core` | Dependency rule green (mutation-checked); `ControlApiInv*` real-HTTP tests green |
| **6** | **Ports + bridge.** Introduce `DatasetProvider`, `CasePort`, `AlertPort`; `la-inspecto` implements them from `DatasetRead`, `ObjectAccess`, `AlertService`; `AnnotationTargets`, `PendingChanges` and the Alert-Rule-bound Investigation code move behind it | LA real-HTTP tests green with all three ports bound **and** with each port unbound (feature reports absent, 503/capability off — never a stack trace) |
| **7** | **Reactor gate.** One full `mvn -o clean test -Pedition-enterprise` plus UI lint/test/build, in a clean worktree at HEAD | Operator-asked GAUNTLET; not once per step |

Steps 1 and 2 are independent and can run in parallel lanes; 3 precedes 4–6; 5 and 6 can split by file set.
**Size:** step 1 S · 2 S · 3 M · 4 S · 5 M · 6 M. Total **M–L**, down from the feasibility plan's L.

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

## 6. References

[`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.3 (target architecture), §7.8 (phases), §7.11 and
§7.12 (the grounded edges this design starts from) · [`la-separation-execution-plan.md`](la-separation-execution-plan.md)
Stage 3 · [`../okf/backend/editions/editions-model.md`](../okf/backend/editions/editions-model.md) (the static profile
module list that must stay in step).
