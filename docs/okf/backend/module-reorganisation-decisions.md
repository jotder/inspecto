---
type: Decision
title: Module reorganisation - decision record (D-MR1..D-MR12)
description: The decisions, declined items, deliberate exceptions and offering map behind the module reorganisation (MODULE-REORG-1), with the preconditions for revisiting each declined item.
resource: docs/okf/backend/module-taxonomy.md
tags: [architecture, modules, decisions, offering, decision-kernel]
timestamp: 2026-10-09T00:00:00Z
---

# Module reorganisation - decision record

The model itself (axes, manifest, gates, Offerings, packaging) is [module-taxonomy.md](module-taxonomy.md); the traps are
[module-reorganisation-gotchas.md](module-reorganisation-gotchas.md). Every phase P0..P7 shipped by 2026-10-09; the open
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
- **Not built, filed as rows:** Screening `SCREENING-1`, Regulatory Reporting `REGULATORY-REPORTING-1`, Anomaly Detection `ANOMALY-DETECTION-1`, Real-time Decisioning `REALTIME-DECISIONING-1` (the engine is batch today), Predictive Analytics `PREDICTIVE-ANALYTICS-1`; packs `PACK-AML-1`, `PACK-MOBILE-MONEY-1`, later Sales & Marketing BI; `TELCO-FRAUD-CONTENT-GAPS-1`.
- **Packs are content, mostly.** A **function pack** (RA, FM, Compliance Audit, BI, LA) is domain-neutral Space Templates that `requires` add-ons; an **industry pack** (telecom ASN.1, mobile money, fixed-line, IPDR, retail, supply chain) holds the vertical's formats, entity types, reference data and field mapping. A sellable solution is an Offering composing both. Binding rules: a new pack needs zero platform edits; generic engines carry no domain vocabulary; a domain module is the exception. The ASN.1 decoder is therefore a Provider in the Telecom industry pack (`telecom-asn1`), not Base.
- **Tiers** are posture, not features: Personal (single user, no IAM), Professional (IAM, HTTPS, RBAC, Postgres, backup, `/metrics`, events feed), Enterprise (ABAC, enforced Space isolation, shared stores, HA).

## Decision Kernel (D-MR12)
No general rule engine (a second language, a large dependency, a poor fit for set-based rules that run inside DuckDB). One shared base capability instead, canonical names **Decision Kernel** (Condition Language + when/if/then model + Consequence registry; not "rule engine", not "policy engine") and **Condition Language** (the **condition tree** `ConditionTree` / `ConditionSql` / `query-eval.ts`).
- **The spike's finding:** the platform had TWO condition languages and the richer one was not `Conditions` (`inspecto-util`, Access Policies only). The tree already had both backends (in memory + DuckDB SQL), a UI builder and three kinds; `Conditions` stays as a text notation. The agent's `EscalationPolicy` is an LLM retry ladder, not an escalation rule: it stays in the agent.
- **Tree extensions (step 2):** `negate` on a group, `valueField` (field-to-field), `ignoreCase`, `matches` (regex, <= 256 chars, no lookaround/back-references/possessive); see [decision-rules](control-plane/decision-rules.md).
- **Adopted (2026-10-07):** Expectation `non_null`/`range`/`regex`, Tag and Case Rule filters, Notification Rule match, Risk factor `filters` (additive optional `when`), Escalation match, with their editors' `when` UI. Alert Rule, Decision Rule and Expectation `condition` were already on the tree.
- **Consequence registry (slice 1):** `ConsequenceProvider` is an open contribution point (`provides.consequences`); a KNOWN consequence whose module is absent reports `unavailable` naming the module, never `executed`; `GET /decision-rules/consequences` lists them. It takes the WHOLE consequence map, not just `params`. Still unregistered: Alert Rule actions, Tag/Case rule effects, Notification channels, the Escalation target; `invoke-api` stays in `DecisionRoutes` until Action Requests is a contribution.
- **Filed outside the question:** Decision Rules build `DELETE`/`UPDATE`/`CREATE TABLE AS`/`COPY` by string concatenation of `ConditionSql` output, guarded by escaping only: row `DECISION-RULE-SQL-GUARD-1`.

## Declined, with the precondition to revisit
| Item | Why declined | Revisit only if |
|---|---|---|
| **Decision Kernel step 7** - Access Policies' `Conditions` text compiled into the tree (2026-10-08) | security: the tree is fail-open where `Conditions` fails closed | the operator asks. Precondition status: strict mode + equivalence corpus BUILT 2026-10-09 (`ConditionTree.matchedStrict`/`filterStrict`/`validateStrict`, `ConditionSql.predicateStrict`; `ConditionsTreeEquivalenceTest` + golden `platform/inspecto-engine/src/test/resources/conditions-tree-equivalence.golden.txt`); corpus verdict over 50136 context x expression pairs (12 contexts, 4178 expressions): EQUAL 29768, DIVERGENT 5476, UNTRANSLATABLE 14892; 25 malformed sources fail identically in `Conditions.parse` and the translator; remaining blockers: the tree needs a collection-membership operator (`in`/`contains` over list attributes, 250 + 358 scalar-stringify pairs), missing-attribute semantics (a missing attribute is `null` in `Conditions`, an absent key is FALSE in strict: 3882 pairs), null vs empty-string (300), type inference from text vs strict typing (`'5' == 5`, `'05' == '5'`: 660), case-insensitive substring (26), and expressions the tree cannot express at all (list literals / `literal contains ref` / `null` members / the empty-string operand: 14892 pairs). Step 7 would still need those operators/semantics decided (or the divergences accepted) |
| **RBAC capability contribution** (P1-D3) | only 8 of 219 capabilities are module-exclusive (4 `exchange`, 4 `la-api`); moving them edits `Roles.CAN_*`, the `SEED` grants and the literal-only scanner guards, and makes the role vocabulary depend on the classpath. Capabilities therefore stay OUT of `provides` | a module with many exclusive capabilities appears (then a `CapabilityContributor` SPI in `auth-spi`, string literals only, because of the `Roles` / `CapabilityManifest` / contributor init cycle) |
| **Renaming `ops` to `incidents`** | measured blast radius (~177 files / 484 lines in `inspecto-ops` alone, plus packages, SPA lines, docs) for a retitle | never needed: the `ops` id, packages and directory stay |
| **In-flight cancellation** of a disabled module's Run (`MODULE-REORG-P4-3`) | needs a per-module start/stop lifecycle contract: a decision | a switchable module owns a long-lived subscription, or cancellation is requested |
| **Workflow & SLA slice 2 step 2** (second governed source, `GovernedItemProvider` SPI) | none of recon Breaks (no priority/attributes/row version), Entity List entries (no review state) or Investigations (no state/priority) has the lifecycle; and `SlaPolicy` / `EscalationRule` / `GovernanceRegistry` are keyed by the closed `ObjectType` enum, so a provider kind needs a `kind`-string key change plus a persistence + policy-key design first | a second governed source with the full lifecycle is wanted; step 1 shipped (`SlaDecisions` over a `GovernedItem` view in `inspecto-workflow`; ops keeps the writes, events and cap) |
| Serving the OpenAPI document merged at boot; module-only schemas in fragments | the Personal build has no module fragments on its classpath and absent modules' paths must stay documented as 503 stubs | the Personal classpath question is answered |
| Hand-authored EDITIONS capability rows as generated | a generated table that cannot express a decision makes its guard revert it | never; the module/add-on/posture/jar tables ARE generated |

## Deliberate exceptions and behaviour changes
Breaking changes are free on any surface (nothing after 3.x was in production): no shims, no compatibility layers.
- **Personal no longer ships** Kafka, ASN.1 parsing, Reconciliation, Risk Scores (`/risk-scores*`), Case Management or Action Requests (it used to serve an empty Action Requests list and could create or decide nothing; now the module is absent and its paths answer 503 `CAPABILITY_UNAVAILABLE` naming the module). Confirmed by the operator 2026-10-07 ("Confirm as built"); the six affected EDITIONS cells were re-confirmed 2026-10-09 against the generated matrix, the Personal classpath and the boot proof (no cell needed a fix).
- **Personal gains durable Alerts** (operator 2026-10-07): Alert history and restart-safe de-duplication on EVERY edition through the Alert-owned `AlertStore` (`DbAlertStore` / `InMemoryAlertStore`; family `ALERTS`, default backend `db`), no longer stored as an operational object. `ObjectType.ALERT` is retired; an unknown stored type loads inert. A one-off `AlertMigration` adopts a legacy ALERT object (delete it when a release containing `2879a70b6` has shipped: row `MODULE-REORG-P7-INCIDENTS`). Resolved Alert rows are purged by `alert_purge`.
- **Operator ack / resolve routes** exist for stored Alerts; the Event bridge (`EventAlertBridge`) moved to `inspecto-engine`.
- **Unmodelled-key policy (P4):** every writer that rebuilds a config from a typed `toMap()` keeps `x-` keys and refuses any other unmodelled key 422 `ERR_UNKNOWN_CONFIG_KEY`, so a save never silently drops config.
- **The telecom-asn1 split packages** (`ingester`, `parse`) were first deliberate, then retired (P3g, `com.gamma.telecom.asn1`).

## Open rows (BACKLOG section Module architecture)
`MODULE-REORG-1` (umbrella) - `MODULE-REORG-P3-THIN-JARS` (operator: production keystore, secrets, TSA URL) - `MODULE-REORG-P4-3` (no work item) - `MODULE-REORG-P7-INCIDENTS` (`AlertMigration` deletion; Workflow & SLA second source) - `MODULE-REORG-P7-KERNEL` (step 7 declined; small leftovers).
