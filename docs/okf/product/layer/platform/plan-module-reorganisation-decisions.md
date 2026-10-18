---
type: Decision
title: Module reorganisation - decision record (D-MR1..D-MR12)
description: The decisions, declined items, deliberate exceptions and offering map behind the module reorganisation (MODULE-REORG-1), with the preconditions for revisiting each declined item.
resource: docs/okf/product/layer/platform/architecture-module-taxonomy.md
tags: [architecture, modules, decisions, offering, decision-kernel]
timestamp: 2026-10-18T00:00:00Z
---

# Module reorganisation - decision record

The model itself (axes, manifest, gates, Offerings, packaging) is [architecture-module-taxonomy.md](architecture-module-taxonomy.md); the traps are
[architecture-module-reorg-gotchas.md](architecture-module-reorg-gotchas.md). Every phase P0..P7 shipped by 2026-10-09; the open
items are the `MODULE-REORG-*` rows of `docs/BACKLOG.md` (listed at the end). The plan that carried the work is archived
(`docs/archived-documents/plans-archive/module-architecture-reorg-plan.md`): provenance only, it holds the per-slice
as-built narrative, the 2026-10-06 measured baseline and the refuted premises.

## Why these decisions (the plan's critique of the external guidelines)
Three axes per module, not three kinds; the unit of sale is a capability including **content** (Space Templates, packs), not a
jar; contract modules may keep logic that is part of the contract's meaning (judged by stability, not LOC); a module boundary
only for optional shipping, a distinct third-party footprint, swappability or test isolation; editions are one dimension
beside add-ons, deployment posture, identity mode and scale; the three gates sit at the edges, never inside feature code;
the manifest declares requirements and contributions, **availability is computed from what bound**; no Pact / OCI /
schema-per-module (in-process TCKs instead); removal semantics are designed, not discovered; and the real itemization blocker
is the **closed central registry** (route list, 503 stubs, feature flags, SPA nav sets, capabilities, governable kinds,
OpenAPI, edition lists, the jlink set, operational store families), so each is opened into a contribution point before code moves.
Vocabulary: **Platform module** (horizontal module) is distinct from **Platform Service** (grantable engine facility) and from
**capability** (RBAC); see GLOSSARY section 15.

## Decisions (operator, 2026-10-06)
| # | Question | Decision and outcome |
|---|---|---|
| D-MR1 | Contract modules | Keep three (`audit-spi`, `auth-spi`, `http-spi`); policy and state moved out (`AccessPolicies`, `EgressPolicy`, `AuditTrail`, `InMemoryEventStore`); `SpiSlot`, DTO validation, `ErrorCodes` stay. Shipped as the P7 contracts split: `com.gamma.control` no longer spans modules; `AuditChain` stays split by design (format + verifier in audit-spi, writer in `inspecto-event`) |
| D-MR2 | Regroup directories | Yes, once. Done 2026-10-07 after path-agnostic guards (`tools/reactor-modules.mjs`, `ReactorModules`): `spi/ platform/ features/ la/ providers/`, artifactIds UNCHANGED, so `-pl :artifactId` |
| D-MR3 | Licence / entitlement engine | None. Installed / Enabled / Permitted gate everything; `entitlementKey` reserved in the manifest |
| D-MR4 | Manifest | TOON at `META-INF/inspecto/module.toon` |
| D-MR5 | `metrics` + `events` | Merged into one Observability module (included from Professional up) |
| D-MR6 | Split `inspecto-security` | Done: OIDC authenticator + token relay, secrets provider, geo-country resolver |
| D-MR7 | JPMS | No: the split-package guard and signed per-module jars give the boundary |
| D-MR8 | Thin per-module jars | Yes: 14 thin core jars + `inspecto.jar` (processor + third-party), one jar per optional module, per-jar SBOM, per-jar signing (mechanism built) |
| D-MR9 | What is sold separately | The offering map below |
| D-MR10 | Per-Space enablement | Yes: `modules.toon`, `GET\|PUT /settings/modules`, 404 `MODULE_DISABLED` |
| D-MR11 | Where Incidents sit | Its own add-on, included from Professional up; never Personal |
| D-MR12 | One Decision Kernel | Yes, spike first; Consequence registry first, Condition Language adopted kind by kind (below) |

### P1 decision log
- **P1-D1** the ~58 built-in route modules stay an explicit ordered list (first-match order is load-bearing; making them `ServiceLoader` types would cost visibility and buy no itemization); optional modules already append through `OptionalSpi.all(RouteModule.class)`.
- **P1-D2** governable kinds opened first (`GovernableKindProvider` in `inspecto-http-spi`; entity-list contributes `entity-list`); an absent module's kind is not governable (a policy naming it is refused 422).
- **P1-D3** RBAC capability contribution deferred, later DECLINED (see below).
- **P1-D4** `features{}` / SPA gating: `RouteModule.featureIds()`, collected only after a successful `register()`; the SPA `navFeature` replaced the three id sets.

## Offering map (D-MR9)
A capability is a separately saleable add-on only if all five hold: a distinct buyer or budget; the base stays coherent without it; separable at reasonable cost; its absence breaks no security or compliance promise; it carries its own cost or risk (third-party libs, LLM, DB, egress). Otherwise it is Base.
- **Base (every Offering):** ingest, Collectors (file/SFTP), parsing, Pipelines, Consignments, schema and Catalog, Expectations, **Alerts**, in-app notifications, Studio, Jobs, Decision Rules, Spaces, audit log, maker-checker hold, the shared condition evaluation. Alerts and Incidents are separate modules; Incidents does not need Case Management. Chain: Signals, Alerts, Incidents (promoted through `IncidentAccess`), optional Cases and Tasks.
- **Add-ons (domain-neutral):** Incidents (Professional up; carries the Enterprise Postgres-mandatory rule for operational objects), Case Management (requires Incidents + Workflow & SLA), Reconciliation, Scoring & Lists (`risk` + `entity-list`), Action Requests (four-eyes outbound calls, raisable from any module), Workflow & SLA, Link Analysis & Investigations (`la-*`, `geo-link`), AI Assist & Intelligence (`agent`, `agent-hosted`, `intelligence`), Integration & Delivery (notify channels, Postgres publish, webhooks), Premium Connectors (Kafka only: `connectors-kafka`; object stores and DB export stayed in core `connectors` by the footprint test), Multi-entity / Group (`exchange`).
- **Not built, filed as rows:** Screening `SCREENING-1`, Regulatory Reporting `REGULATORY-REPORTING-1`, Anomaly Detection `ANOMALY-DETECTION-1`, Real-time Decisioning `REALTIME-DECISIONING-1` (the engine is batch today), Predictive Analytics `PREDICTIVE-ANALYTICS-1`; later Sales & Marketing BI. The telecom fraud content gaps (`TELCO-FRAUD-CONTENT-GAPS-1`: recharge fraud, data-charging bypass, internal fraud) shipped 2026-10-10 inside the `telco-fraud` Space Template ([spaces §3.5](../../module/config/plan-spaces.md)). Manual credit-adjustment fraud (staff-posted ADJUSTMENT credits) is filed as `TELCO-FRAUD-MANUAL-ADJUSTMENTS-1`. The Mobile Money industry pack shipped 2026-10-09 as the `mobile-money` Space Template ([spaces §3.5.3](../../module/config/plan-spaces.md)). The AML function pack shipped 2026-10-10 as the `aml` Space Template ([spaces §3.5.4](../../module/config/plan-spaces.md)).
- **Packs are content, mostly.** A **function pack** (RA, FM, Compliance Audit, BI, LA) is domain-neutral Space Templates that `requires` add-ons; an **industry pack** (telecom ASN.1, mobile money, fixed-line, IPDR, retail, supply chain) holds the vertical's formats, entity types, reference data and field mapping. A sellable solution is an Offering composing both. Binding rules: a new pack needs zero platform edits; generic engines carry no domain vocabulary; a domain module is the exception. The ASN.1 decoder is therefore a Provider in the Telecom industry pack (`telecom-asn1`), not Base.
- **Tiers** are posture, not features: Personal (single user, no IAM), Professional (IAM, HTTPS, RBAC, Postgres, backup, `/metrics`, events feed), Enterprise (ABAC, enforced Space isolation, shared stores, HA).

## Decision Kernel (D-MR12)
No general rule engine (a second language, a large dependency, a poor fit for set-based rules that run inside DuckDB). One shared base capability instead, canonical names **Decision Kernel** (Condition Language + when/if/then model + Consequence registry; not "rule engine", not "policy engine") and **Condition Language** (the **condition tree** `ConditionTree` / `ConditionSql` / `query-eval.ts`).
- **The spike's finding:** the platform had TWO condition languages and the richer one was not `Conditions` (`inspecto-util`, Access Policies only). The tree already had both backends (in memory + DuckDB SQL), a UI builder and three kinds; `Conditions` stays as a text notation. The agent's `EscalationPolicy` is an LLM retry ladder, not an escalation rule: it stays in the agent.
- **Tree extensions (step 2):** `negate` on a group, `valueField` (field-to-field), `ignoreCase`, `matches` (regex, <= 256 chars, no lookaround/back-references/possessive); see [decision-rules](../../module/processor/spec-decision-rules.md).
- **Adopted (2026-10-07):** Expectation `non_null`/`range`/`regex`, Tag and Case Rule filters, Notification Rule match, Risk factor `filters` (additive optional `when`), Escalation match, with their editors' `when` UI. Alert Rule, Decision Rule and Expectation `condition` were already on the tree.
- **Consequence registry (slice 1):** `ConsequenceProvider` is an open contribution point (`provides.consequences`); a KNOWN consequence whose module is absent reports `unavailable` naming the module, never `executed`; `GET /decision-rules/consequences` lists them. It takes the WHOLE consequence map, not just `params`. Still unregistered: Alert Rule actions, Tag/Case rule effects, Notification channels, the Escalation target; `invoke-api` stays in `DecisionRoutes` until Action Requests is a contribution.
- **Decision Rule write SQL: values are bound (`DECISION-RULE-SQL-GUARD-1`, closed 2026-10-09).** The spike found that `DecisionRuleApplier` built `DELETE`/`UPDATE`/`CREATE TABLE AS`/`COPY` by concatenating `ConditionSql` output, guarded by escaping only. A break-out probe (`DecisionRuleApplierInjectionTest`: quotes, `;`, comments, `$$`, `\'`, hostile field names including NUL and newline, a tag value, a rule name and a quarantine root containing `'`) found **nothing escaping**: `''` doubling and `SqlIdent.q` held, and a NUL truncates the statement into a parser error (rule skipped), never a shorter valid one.
  - **Decision: bind the values.** `ConditionSql.predicateBound(when)` returns `Bound(sql, params)`: every operand (string, number, regex, date, each `in` member) is a `CAST(? AS VARCHAR|DOUBLE)` parameter; only quoted identifiers and fixed SQL reach the text. The applier runs every statement as a `PreparedStatement` (count, tag `UPDATE` with the tag value bound too, the staging `CREATE TABLE AS`, `DELETE`); `COPY` is kept parameter-free, so quarantine stages the matched rows in `__dr_quarantined` and copies that table, as route already did with `__dr_routed`. Table names go through `SqlIdent.q`.
  - **Not chosen:** a `SqlGuard` check of the statement. `SqlGuard` admits a single read-only `SELECT` only, and its keyword scan reads inside string literals, so a rule value such as `'update'` or `'set'` would be refused; it guards the wrong shape. Confining the writer adds nothing: the table is the caller's in-flight table, never rule-authored.
  - **Also fixed:** a `NaN`/`Infinity` operand passes `isNumeric` and used to print as a bare word DuckDB reads as a column, so the rule failed and was silently skipped. It is a bound `DOUBLE` now, and a `'…'::DOUBLE` literal in the inline `predicate()`.
  - **Expectations bound too:** `ExpectationEvaluator.countSql` returns a `Bound` and runs through `SqlSandbox.preparedStatement`.
  - **Still inline** (`ConditionSql.predicate()`, escaped literals; all read-only): the AI `query` tool in `InspectoTools`, whose output IS saved SQL text, so it can never bind; LA's `DatasetProvider.predicate` port (`EngineDatasetProvider`, ~56 references across la-api/la-core/la-storage; row `LA-FILTER-SQL-BIND-1`). The Decision Kernel's write path must use `predicateBound`.
  - **BI / Measure SQL bound too (`MEASURE-SQL-BIND-1`, closed 2026-10-09).** `MeasureCompiler.compile(spec)` returns `Compiled(sql, params)`; `render(spec)` keeps the literal form for DISPLAY (the SQL `/bi/query` echoes) and save-time validation (`RiskScoreModel`), never execution. A flat `filters` value keeps its literal's type: an integral number is `CAST(? AS BIGINT)`, any other number `CAST(? AS DOUBLE)` (a bare `?` would take an INTEGER column's type and round `5.5`), a boolean `CAST(? AS BOOLEAN)`, a string a bare `?` that DuckDB types from the column as it typed the string literal; `when` goes through `predicateBound`. `QueryExecutor.Request.binds` is `List<?>` set with `setObject` (Rule Template strings behave as before). Callers: `BiRoutes` (the bound-query `WITH` prefix carries no placeholders, so the Measure's params stay in order), `ShareRoutes`, `KpiEvaluator`, `RiskScoreEvaluator`, `ScreeningJobType`, `DatasetMeasureProbe` (Alert Rule Measures, both reads), `ReportJob`. `MaterializeTask` (`COPY`) and `RowShaper` summarize (`CREATE TABLE AS`) build specs with no values and call `Compiled.unboundSql()`, which refuses a spec that carries any. Reconciliation does not use the compiler. `MeasureCompilerBindTest` runs the bound and rendered forms side by side over VARCHAR/INTEGER/DOUBLE/DECIMAL/BOOLEAN/TIMESTAMP columns and sends a quote/`;`/comment value through `=`, `in`, `like` and `when`; mutation-checked (inlining the values again, or binding a fraction as a bare `?`, turns it red).
  - **Mutation-checked:** inlining the values again turns only the bound-parameter test red; also removing the `''` doubling turns the break-out tests red (`OR '1'='1` empties the table); breaking the inline escape alone, with binding in place, leaves every applier probe green.

## Declined, with the precondition to revisit
| Item | Why declined | Revisit only if |
|---|---|---|
| **Decision Kernel step 7** - Access Policies' `Conditions` text compiled into the tree (2026-10-08) | security: the tree is fail-open where `Conditions` fails closed | the operator asks. Precondition status: strict mode + equivalence corpus BUILT 2026-10-09 (`ConditionTree.matchedStrict`/`filterStrict`/`validateStrict`, `ConditionSql.predicateStrict`; `ConditionsTreeEquivalenceTest` + golden `platform/inspecto-engine/src/test/resources/conditions-tree-equivalence.golden.txt`); corpus verdict over 50136 context x expression pairs (12 contexts, 4178 expressions): EQUAL 29768, DIVERGENT 5476, UNTRANSLATABLE 14892; 25 malformed sources fail identically in `Conditions.parse` and the translator; remaining blockers: the tree needs a collection-membership operator (`in`/`contains` over list attributes, 250 + 358 scalar-stringify pairs), missing-attribute semantics (a missing attribute is `null` in `Conditions`, an absent key is FALSE in strict: 3882 pairs), null vs empty-string (300), type inference from text vs strict typing (`'5' == 5`, `'05' == '5'`: 660), case-insensitive substring (26), and expressions the tree cannot express at all (list literals / `literal contains ref` / `null` members / the empty-string operand: 14892 pairs). Step 7 would still need those operators/semantics decided (or the divergences accepted) |
| **RBAC capability contribution** (P1-D3) | only 8 of 219 capabilities are module-exclusive (4 `exchange`, 4 `la-api`); moving them edits `Roles.CAN_*`, the `SEED` grants and the literal-only scanner guards, and makes the role vocabulary depend on the classpath. Capabilities therefore stay OUT of `provides` | a module with many exclusive capabilities appears (then a `CapabilityContributor` SPI in `auth-spi`, string literals only, because of the `Roles` / `CapabilityManifest` / contributor init cycle) |
| **Renaming `ops` to `incidents`** | measured blast radius (~177 files / 484 lines in `inspecto-ops` alone, plus packages, SPA lines, docs) for a retitle | never needed: the `ops` id, packages and directory stay |
| **In-flight cancellation** of a disabled module's Run (`MODULE-REORG-P4-3`) | needs a per-module start/stop lifecycle contract: a decision | a switchable module owns a long-lived subscription, or cancellation is requested |
| **Workflow & SLA slice 2 step 2** (second governed source, `GovernedItemProvider` SPI) - **DECLINED 2026-10-11 (operator)** (research memo below is the revisit playbook) | no demand: a promoted Break already gets a per-Break SLA through `POST /recon/promote` (`BREAK-INCIDENT-1`) into an Incident governed by the INCIDENT sla-policy / escalation rules; no consumer has asked for SLAs on never-promoted Breaks | a named consumer wants an SLA on unpromoted Breaks (then the memo's plan applies, starting at Q2); step 1 shipped (`SlaDecisions` over a `GovernedItem` view in `inspecto-workflow`; ops keeps the writes, events and cap) |
| Serving the OpenAPI document merged at boot; module-only schemas in fragments | the Personal build has no module fragments on its classpath and absent modules' paths must stay documented as 503 stubs | the Personal classpath question is answered |
| Hand-authored EDITIONS capability rows as generated | a generated table that cannot express a decision makes its guard revert it | never; the module/add-on/posture/jar tables ARE generated |

## Workflow & SLA slice 2 step 2 - research memo (2026-10-11, read-only) - revisit playbook (step 2 DECLINED 2026-10-11)
Declined by the operator 2026-10-11 (no demand; promote-to-Incident covers Breaks). The memo below is kept as the playbook **if revisited**; precondition to revisit: a named consumer wants an SLA on unpromoted Breaks. The archived plan's "P7 Workflow & SLA slice 2 as built" note is never edited; its stale claims are corrected here.
- **Corrections to the stated blockers.** *Priority* is not needed (`SlaPolicy`'s `"*"` fallback, `SlaPolicy.java:33,39`;
  `SlaDecisions.java:65-67`). A *per-row version* is not needed (`ReconStateStore` serialises on a static JVM `LOCK`; the
  store is plain JSON via `AtomicFiles`, not signed). **A missed blocker:** `Workflow` is keyed by `ObjectType` too
  (`Workflow.java:35`, `fromComponent` :263-271; `GovernanceRegistry.Snapshot` :43,124; `SlaDecisions` :50,83), so a `kind`
  key change alone is not enough; the cheaper fix is a small `Lifecycle { initialState(); isTerminal(s) }` interface that
  `Workflow` implements.
- **Why demand was the question (answered: none).** A Break already gets a per-Break SLA through `POST /recon/promote` (`BREAK-INCIDENT-1`):
  the Incident is governed by the INCIDENT policy. A second source only adds an SLA on never-promoted Breaks.
- **Candidate sources.** *Break* - viable: status `open/assigned/resolved/auto_closed`, assignee, `firstSeenAt`; add an
  optional `sla` attribute map; zero migration; no new module edge (recon already imports `com.gamma.workflow`; recon, ops
  and workflow are not in `check-module-deps` `ALLOWED`). *Investigation* - immutable header, so it needs a state machine
  and a compare-and-set in the FS and Pg stores; a provider in the governed `inspecto-la-api` would FAIL `check-module-deps`
  (`ALLOWED['inspecto-la-api']` lacks `inspecto-workflow`, `check-module-deps.mjs:38`): L, high risk. *Entity List entries* -
  not viable (a canonical-string set, no per-entry record).
- **Outcome.** Closed as declined 2026-10-11 (a docs-only fix). If revisited (demand exists), Breaks in five
  steps (~1.5 shifts): (1) re-key plus the `Lifecycle` seam (S); (2) a batch-shaped `GovernedItemProvider` SPI, a sweep via
  `ModuleGate`, `provides.governedKinds` and a per-sweep breach cap (S-M); (3) the Break `sla` field (S); (4) a recon
  provider kind `RECON_BREAK` that excludes promoted Breaks (M); (5) SPA and docs (S).
- **Calls if revisited (Q1-Q6).** Q1 (demand for an SLA on unpromoted Breaks) was answered "no" 2026-10-11; Q2 which source; Q3 the known-kind set and
  the absent-module policy (inert, per P4e); Q4 event identity (reuse `OBJECT_SLA_BREACH` / `OBJECT_ESCALATED` with
  `objectType=<kind>`?); Q5 flood control (one recon run can open 50,000 Breaks; escalations are capped at 500 per sweep but
  breaches are uncapped, `ObjectService.java:81`); Q6 a batch SPI (a per-item update would rewrite a file of up to 200,000
  Breaks once per Break).

## Deliberate exceptions and behaviour changes
Breaking changes are free on any surface (nothing after 3.x was in production): no shims, no compatibility layers.
- **Personal no longer ships** Kafka, ASN.1 parsing, Reconciliation, Risk Scores (`/risk-scores*`), Case Management or Action Requests (it used to serve an empty Action Requests list and could create or decide nothing; now the module is absent and its paths answer 503 `CAPABILITY_UNAVAILABLE` naming the module). Confirmed by the operator 2026-10-07 ("Confirm as built"); the six affected EDITIONS cells were re-confirmed 2026-10-09 against the generated matrix, the Personal classpath and the boot proof (no cell needed a fix).
- **Personal gains durable Alerts** (operator 2026-10-07): Alert history and restart-safe de-duplication on EVERY edition through the Alert-owned `AlertStore` (`DbAlertStore` / `InMemoryAlertStore`; family `ALERTS`, default backend `db`), no longer stored as an operational object. `ObjectType.ALERT` is retired; an unknown stored type loads inert. A one-off `AlertMigration` adopts a legacy ALERT object (delete it when a release containing `2879a70b6` has shipped: row `MODULE-REORG-P7-INCIDENTS`). Resolved Alert rows are purged by `alert_purge`.
- **Operator ack / resolve routes** exist for stored Alerts; the Event bridge (`EventAlertBridge`) moved to `inspecto-engine`.
- **Unmodelled-key policy (P4):** every writer that rebuilds a config from a typed `toMap()` keeps `x-` keys and refuses any other unmodelled key 422 `ERR_UNKNOWN_CONFIG_KEY`, so a save never silently drops config.
- **The telecom-asn1 split packages** (`ingester`, `parse`) were first deliberate, then retired (P3g, `com.gamma.telecom.asn1`).
- **The jlink hand list is retired** (2026-10-18, taken on the operator's behalf): `package.ps1` links the derived set alone and **fails closed** on a lock mismatch or a missing section, rather than falling back to a hand list; `tools/jlink-runtime-extra.txt` stays as the one hand-kept list, because each entry carries its WHY; every edition must have a lock section (`--check`, CI guards job). Reverse by restoring the union block in `package.ps1` (git history).

## Open rows (BACKLOG section Module architecture)
The umbrella row `MODULE-REORG-1` was closed 2026-10-18 (decided under operator delegation): every phase P0..P7 shipped, its model is this record plus [architecture-module-taxonomy.md](architecture-module-taxonomy.md), and its open work is tracked only in the sub-rows. `MODULE-REORG-P3-THIN-JARS` (operator: production keystore, secrets, TSA URL) - `MODULE-REORG-P4-3` (no work item) - `MODULE-REORG-P7-INCIDENTS` (`AlertMigration` deletion; the Workflow & SLA second source was declined 2026-10-11, see the memo above). `MODULE-REORG-P7-KERNEL` closed 2026-10-09: steps 1, 3–6 and the Escalation Rule edit UI shipped; step 7 and the five unregistered Consequences declined (BACKLOG §6).
