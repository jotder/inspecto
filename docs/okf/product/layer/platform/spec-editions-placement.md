---
type: Spec
title: Editions — engineering specification — LA feature placement, the Matrix and the Offerings matrix
description: Part 1 of 5 of Editions — engineering specification: LA feature placement, the Matrix and the Offerings matrix
resource: offerings/
tags: [editions, packaging, offerings, gating, decisions]
timestamp: 2026-11-06T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
---

# Editions — engineering specification — LA feature placement, the Matrix and the Offerings matrix

Part 1 of 5 of [Editions — engineering specification](spec-editions.md) · next: [Preview, Basic, the data layer and the assembly model](spec-editions-tiers.md).

## Inspecto LA feature placement (LA-EDITIONS-1 §2, decided 2026-10-17)

Inspecto LA (Link Analysis) is sold in the same editions. Principle: never sell weaker analysis or weaker evidence —
editions differ by **who** (one analyst, a team, an organisation), **how much data**, and **how much governance**
(ED-7: placement accepted as proposed). How Basic withholds the Investigation surface: *Basic — the Link Analysis
explorer* below.

| Area | Feature | Basic | Standard | Advanced | Preview |
|---|---|---|---|---|---|
| Data | Add data: upload a file, review its columns, a Dataset (one default Pipeline, no transformation; `BASIC-ONBOARD-1`) | ✅ | ✅ | ✅ | ✅ |
| | An import's inbox: *Import waiting files*, or *Watch this folder* (a fixed 30 s poll, off by default) | ✅ | ✅ | ✅ | ✅ |
| | *Choose a table*: a Postgres table snapshot, refreshed with *Refresh now* only (no poll since 2026-11-05) | ✅ | ✅ | ✅ | ✅ |
| | *Keep this up to date* on a chosen schedule (watched folder, SFTP), other databases and remote systems | — | ✅ | ✅ | ✅ |
| Explore | Graph studio, projections from any Dataset, saved views | ✅ | ✅ | ✅ | ✅ |
| | Find paths, neighbours, "Investigate a number", connect seeds | ✅ | ✅ | ✅ | ✅ |
| | Temporal and branching patterns, pattern packs | ✅ | ✅ | ✅ | ✅ |
| | Value Measures | ✅ | ✅ | ✅ | ✅ |
| | Geo map, routes, co-location | — | ✅ | ✅ | ✅ |
| | Browser graph algorithms | ✅ | ✅ | ✅ | ✅ |
| | Entity Lists, identity resolution | ✅ | ✅ | ✅ | ✅ |
| | Entity profile pages | — | ✅ | ✅ | ✅ |
| | Reference data beside a node | ✅ | ✅ | ✅ | ✅ |
| | Export (PNG, CSV), snapshots (not evidence-grade) | ✅ | ✅ | ✅ | ✅ |
| | Link Analysis widgets on dashboards (offer hidden on Basic; routes stay open) | — | ✅ | ✅ | ✅ |
| | Industry packs (Telecom, Mobile Money) | ✅ | ✅ | ✅ | ✅ |
| | Masking (the default follows sign-in, never the LA level: `LA-ONBOARD-1` OB-6) | off by default (`none`); a mode the Space states is display only (no sign-in: anyone may reveal) | ✅ enforced, default `typed` (with or without the Investigations add-on) | ✅ | ✅ |
| Investigate | **Investigations add-on** `INS-AO-LA-INVESTIGATIONS` (`LA-ONBOARD-1` OB-5): billable, enforced at runtime, included by default; every Investigate, Share, Scale and Operate row below needs it. Without it the install runs Link Analysis at the **Explorer** level: the toolbox shows only its *On screen* tab (the *Whole Dataset* and *Investigation* tabs and the "Also on Whole Dataset" labels are hidden, operator 2026-11-02); other Investigation actions show locked with the reason | — | ✅ default | ✅ default | ✅ default |
| | Investigations: sealed op log, replay, compare, temporal findings | — | ✅ | ✅ | ✅ |
| | Dossier and Dossier bundle with verification | — | ✅ | ✅ | ✅ |
| | Templates, external references | — | ✅ | ✅ | ✅ |
| | Data trust: data strip, Files, coverage, delivery Alerts | — | ✅ | ✅ | ✅ |
| Team | Sign-in, members and roles, four-eyes, audited reveal | — | ✅ | ✅ | demo sign-in |
| | Drafts: parallel analysts, rebase, promote | — | ✅ | ✅ | ✅ |
| Share | Embed links (read-only Investigation view) | — | ✅ | ✅ | ✅ |
| | Case linking, Case history beside a node | — | ✅ | ✅ | ✅ |
| Scale | Server Graph Run (29 algorithms) | — | ✅ | ✅ | ✅ |
| | Link index, daily ingestion | — | ✅ | ✅ | ✅ |
| Operate | Alert Rules on Investigations, standing detection (Basic: the LA app's Alerts area is hidden) | — | ✅ | ✅ | ✅ |
| | Assistant: AI-proposed column mappings | — | ✅ | ✅ | ✅ |
| Integrate | Third-party integration (case managers, fraud products) | — | ✅ | ✅ | ✅ |
| Govern | Investigations on Postgres (multi-node, HA) | — | — | ✅ | ✅ |
| | Policy engine (ABAC; can only narrow) | — | — | ✅ | ✅ |
| | Scale-out, retention, residency (not built yet); legal hold of an Incident's evidence, Investigations included (built 2026-11-17, `LEGAL-HOLD-SCOPE-1`; not edition-gated: it works wherever Incidents do) | — | — | ✅ | ✅ |
| All | Configurable limits (Basic: Space layer; Standard: Space + install; Advanced: all) | ✅ | ✅ | ✅ | ✅ |
| | Standalone Inspecto LA app or inside the console | ✅ | ✅ | ✅ | ✅ |

⚠ **As built (2026-10-17, phases 2–7).** The explorer rows hold in the Basic build (below). Industry packs (phase 4:
the Telecom and Mobile Money packs and the server Domain Profile, `GET/PUT /settings/link-analysis/domain-profile`) live
in the base module `inspecto-entity-store` and the core, so they are in every edition, Basic included (ED-5); the
Telecom ASN.1 decoder (`inspecto-telecom-asn1`, a CDR parser rather than a pack) is Advanced only since 2026-11-17 (DATA-TIERS-1 DT-12; it was Standard and up). Third-party
integration (phases 5–6: `POST /inv/inbound/investigations`, `POST /inv/deep-links/resolve`, `GET/PUT /inv/integration`,
`POST /inv/investigations/{id}/handoff`, the outbound webhooks) is withheld on Basic by the same `investigations` edition
feature (ED-6, `LD-EDITIONS-12`): the routes answer 503 and the webhook tap sends nothing; the SPA `/open` page explains
it. "Investigate a number" on Basic explains that Investigations are a Standard feature and leaves the number
to a projection instead of seeding the canvas. **Case history beside a node** (`POST /inv/case-history`) is Standard and up
**by an explicit gate** (D-LAE1, operator 2026-10-20): it has its own route module (`CaseHistoryRoutes`) naming the
`investigations` feature, so Basic answers `503 CAPABILITY_UNAVAILABLE` "available in Standard and above" at dispatch, and
the SPA hides the section (`LA_FEATURES.investigations`). ⚠ This overrides the archived LA-EDITIONS-1 plan, which left Case
history "explorer" with Basic refusing it only because no Case module was there to read. Release note: an existing Basic
install loses Case history on upgrade; no migration shim (D-LAE12).

**Geo map, routes and co-location are Standard and up (D-LAE5, operator 2026-10-20), by a feature of their own.** `GeoRoutes`
names a second edition feature, `EditionFeatures.GEO_MAP` (`geoMap`), declared by `inspecto-ops` beside `investigations`:
Basic answers `POST /geo/projection` and `POST /geo/routes` `503 CAPABILITY_UNAVAILABLE` "available in Standard and above"
at dispatch, while every other Link Analysis module stays up. ⛔ It is deliberately **not** done through `geoLink`: that
id is the Space-level module switch every one of the Link Analysis route modules shares, so withholding it would take them
all down. `/bootstrap` reports `features.geoMap` (false on Basic, with `geoLink` still true); the console nav (`navFeature:
'geoMap'`), the Geo area of the standalone LA app and the Menu Builder's Geo view key on it, so Basic offers nothing that
only 503s. Release note: an existing Basic install loses the Geo map on upgrade; no migration shim (D-LAE12). The module
switch stays as before: a Space may still turn `geoLink` (all of Link Analysis) off.

**Entity profile pages are Standard and up (D-LAE4, operator 2026-10-25), by a feature of their own.** The two profile
reads, `GET /inv/entity-types/{id}/profile` and `GET /inv/entities/{type}/{key}/profile`, moved to their own route module
`EntityPageRoutes`, which names a third edition feature, `EditionFeatures.ENTITY_PAGES` (`entityPages`), declared by
`inspecto-ops`: Basic answers them `503 CAPABILITY_UNAVAILABLE` "available in Standard and above". The Entity Type list
(`GET /inv/entity-types`), resolve and suggest stay on `EntityProfileRoutes`, open in every edition, because the Basic
Start box, Add data and "Open in Link Analysis" read them. `/bootstrap` reports `features.entityPages`; the console nav
(`navFeature: 'entityPages'`), the Entities area of the standalone LA app and the Open-in-LA page's "Open the Entity" link
key on it. Release note: an existing Basic install loses the Entity profile pages on upgrade; no migration shim (D-LAE12).

**Link Analysis widgets on dashboards are Standard and up as a UI flag only (D-LAE2, operator 2026-10-20).** `EditionFeatures.LA_WIDGETS`
(`laWidgets`), declared by `inspecto-ops`, is reported as `/bootstrap` `features.laWidgets` (true only where `geoLink` is on and the
edition has it). On Basic the Widget builder (Explore) does not offer the *Link analysis* and *Geo map* widget types (the Geo map one
also needs `geoMap`). ⚠ **This is cosmetic, by decision, not access control:** the feature is deliberately not a route feature, the
Link view's projection route (`POST /inv/projection`) stays open on Basic (the Geo map widget's routes are gated by `geoMap`, above), and a widget saved earlier still opens.
Release note: an existing Basic install can no longer add these widget types; no migration shim (D-LAE12).

## Matrix

| Aspect | **Basic** | **Standard** | **Advanced (partially shipped)** |
|---|---|---|---|
| Transport | Plain HTTP; binds **every interface** unless you set `-Dcontrol.bind` — see the note below | HTTPS (`HttpsServer` + keystore; FIPS provider for Gov) | HTTPS, TLS at LB/gateway |
| AuthN | **None** | **Delegated to an external IAM** (Keycloak / WSO2 / Okta / Entra) — app is an OIDC/OAuth2 **resource server** | Same, centralized IAM + token introspection |
| AuthZ | None | **RBAC + ABAC** from IAM token claims/groups | RBAC/ABAC + per-tenant boundaries |
| User mgmt / LDAP / SAML | n/a | **IAM's job** (federates AD/LDAP, brokers SAML) | IAM's job |
| Secrets | env / file | `SecretsProvider` (file + OS keystore, or Vault) | Vault / cloud secrets manager |
| At-rest encryption | optional (volume) | Volume encryption + AES-GCM for stored creds | Volume + shared-store encryption |
| Audit | local append-only logs — **recorded in every edition and readable on Basic** via the core `/audit/search` + `/audit/export` routes (EDG-01 cell 6, 2026-09-08). ⛔ Not the same thing as CP-13's events feed, which IS gated: gating the feed alone would have taken this row's promise away, so the audit projection deliberately stayed in core. | **actor-attributed**, tamper-evident compliance log | shared, centralized audit store |
| State | local disk | local disk (or optional Postgres — driver bundled as the `postgresql.jar` sidecar, selected by `-Dinspecto.db`) | **shared backends** (Postgres / object store / Vault) |
| Scheduler | in-JVM | in-JVM | **distributed coordination** (leader election / locks) |
| Compliance scope | none | SOC 2 / ISO 27001 / FedRAMP / HIPAA / PCI | inherits Standard + multi-node controls |
| Packaging | `inspecto.jar` (processor + third-party), the core thin jars and the `inspecto-connectors.jar` sidecar; `-Dauth.mode` unset (auth-free) | The Basic jars plus the Standard-and-up optional modules (OIDC, secrets, geo, Kafka and ASN.1 connectors, delivery channels, backup, Link Analysis, ops, agent, …) and the `postgresql.jar` sidecar; `-Dauth.mode=oidc`, TLS on. ⚠ **The module list and every jar count are generated, not stated here** — see *Offerings matrix (generated)* below; this cell used to carry hand-typed counts ("seventeen optional modules, 19 jars") that had drifted twice | The Standard jars plus `policy` (ABAC), `la-store-pg` and `intelligence`; `-Dobjects.backend=postgres` (counts: see the generated matrix) — later + shared-store modules, coordinator |

> ### 🔴 Listen address — read this before running Basic on a shared network
>
> **The control plane binds every interface by default, in every edition.** `-Dcontrol.bind=<host-or-IP>`
> restricts it (`-Dcontrol.bind=127.0.0.1` for loopback); an unresolvable value fails the boot rather
> than falling back to the wider address.
>
> ⚠ **This matters most on Basic, which ships no authenticator at all.** Left on the default, a
> Basic install serves an unauthenticated control plane — including the config-write routes — to
> every host that can reach the port. Set `-Dcontrol.bind=127.0.0.1`, or firewall the port, for any
> single-user install.
>
> The default is deliberate (2026-08-29): narrowing it to loopback would silently make every deployed
> Standard and Advanced install unreachable on upgrade, so the flag lets a deployment restrict itself
> rather than changing what an existing one does. ⛔ **This table previously read "bind localhost only"
> for Basic, which the code never enforced** — the claim, not the behaviour, was the defect. Pinned by
> `ControlApiBindTest`.

## Offerings matrix (generated)

The module-by-edition matrix, the add-on table, the deployment posture and the staged-jar counts below are **generated** from
`offerings/*.toon` (the Offerings), the module manifests and `tools/bundle-modules.mjs` by `tools/render-offerings-matrix.mjs`
(`--write` to regenerate, `--check` in CI). Rows the generator cannot derive — transport, secrets, at-rest encryption, audit,
compliance scope, HA and the capability rows — stay hand-authored in the matrix above, because no Offering holds a verifiable
source for them yet (see `docs/okf/product/layer/platform/spec-module-reorg-decisions.md`, *Declined*).

<!-- offerings-matrix:begin -->
> Generated by `tools/render-offerings-matrix.mjs` from `offerings/*.toon`, the module manifests and `tools/bundle-modules.mjs` -
> **do not hand-edit**; fix the Offering or the manifest and run `node tools/render-offerings-matrix.mjs --write`.
> Cells: ✅ the edition stages it · — absent · 🟦 base (always present: shaded into the processor jar or a core thin jar).

### Modules by edition

| Module | Layer | Role | Requires | Basic | Standard | Advanced | Preview |
|---|---|---|---|:-:|:-:|:-:|:-:|
| `audit-spi` - Audit contract | spi | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `auth-spi` - Authentication and capability contract | spi | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `http-spi` - Route module contract | spi | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `access` - Access policy (roles, grants, row scope, audit trail) | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `acquire` - Acquisition and collectors | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `analytics` - Analytics Kernel (canonical statistics and baselines) | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `api` - Public API annotations | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `config` - Config model and codecs | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `engine` - Execution engine | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `entity-store` - Entity store | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `etl` - ETL model and step registry | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `event` - Event model and log | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `graph` - Graph algorithms | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `sql` - SQL guard and helpers | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `util` - Utilities, DuckDB access, TOON helpers, module manifests | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `workflow` - Workflow and SLA models | platform | base | — | 🟦 | 🟦 | 🟦 | 🟦 |
| `action-requests` - Action Requests | features | optional | `notify-channels` | — | ✅ | ✅ | ✅ |
| `agent` - Assistant agent | features | optional | — | — | ✅ | ✅ | ✅ |
| `anomaly` - Anomaly Detection | features | optional | `entity-list` | — | ✅ | ✅ | ✅ |
| `backup` - Backup and restore | features | optional | — | — | ✅ | ✅ | ✅ |
| `case-management` - Case Management | features | optional | `ops` | — | ✅ | ✅ | ✅ |
| `entity-list` - Entity lists | features | optional | `entity-store` | ✅ | ✅ | ✅ | ✅ |
| `exchange` - Exchange (publication and ingestion contracts) | features | optional | — | — | ✅ | ✅ | ✅ |
| `insights` - Business Insights | features | optional | — | ✅ | ✅ | ✅ | ✅ |
| `intelligence` - AI assist and intelligence | features | optional | — | — | — | ✅ | ✅ |
| `mcp` - MCP endpoint | features | optional | — | — | ✅ | ✅ | ✅ |
| `observability` - Observability (metrics endpoint + events feed API) | features | optional | — | — | ✅ | ✅ | ✅ |
| `ops` - Case management and objects | features | optional | — | — | ✅ | ✅ | ✅ |
| `predictive` - Predictive Analytics | features | optional | — | — | ✅ | ✅ | ✅ |
| `realtime` - Real-time Detection | features | optional | `ops` | — | ✅ | ✅ | ✅ |
| `reconciliation` - Reconciliation | features | optional | — | — | ✅ | ✅ | ✅ |
| `regulatory-reporting` - Regulatory Reporting | features | optional | `ops` | — | ✅ | ✅ | ✅ |
| `scoring` - Scoring | features | optional | `entity-list` | — | ✅ | ✅ | ✅ |
| `screening` - Screening | features | optional | `entity-list`, `ops` | — | ✅ | ✅ | ✅ |
| `geo-link` - Geo and link adapters over platform data | la | optional | `la-api`, `la-core` | ✅ | ✅ | ✅ | ✅ |
| `la-api` - Link Analysis and geo routes | la | optional | `la-core`, `la-storage` | ✅ | ✅ | ✅ | ✅ |
| `la-core` - Link Analysis core and ports | la | optional | `graph` | ✅ | ✅ | ✅ | ✅ |
| `la-storage` - Link Analysis storage | la | optional | `la-core` | ✅ | ✅ | ✅ | ✅ |
| `la-store-pg` - Link Analysis PostgreSQL store | la | provider | `la-core` | — | — | ✅ | ✅ |
| `agent-hosted` - Hosted model providers for the assistant | providers | provider | `agent` | — | — | — | — |
| `asn-decoders` - ASN.1 decoders | providers | provider | — | — | — | — | — |
| `connectors` - Collector and export connectors | providers | provider | — | ✅ | ✅ | ✅ | ✅ |
| `connectors-kafka` - Kafka stream connector | providers | provider | — | — | ✅ | ✅ | ✅ |
| `demo-auth` - Demo authenticator | providers | provider | — | — | — | — | — |
| `geo-country` - MaxMind geo-country resolver | providers | provider | — | — | ✅ | ✅ | ✅ |
| `notify-channels` - Notification channels | providers | provider | — | — | ✅ | ✅ | ✅ |
| `oidc` - OIDC authenticator and token relay | providers | provider | — | — | ✅ | ✅ | ✅ |
| `policy` - Attribute-based access policy | providers | provider | — | — | — | ✅ | ✅ |
| `secrets` - File-keystore secrets provider | providers | provider | — | — | ✅ | ✅ | ✅ |
| `telecom-asn1` - Telecom ASN.1 decoder | providers | provider | — | — | — | ✅ | ✅ |
| `processor` - Control plane and processor host | inspecto | base | — | 🟦 | 🟦 | 🟦 | 🟦 |

### Add-ons by edition

| Add-on | Modules / hosted by | Basic | Standard | Advanced | Preview |
|---|---|:-:|:-:|:-:|:-:|
| actionRequests | `action-requests` | — | built | built | built |
| aiAssist | `agent` | — | built | built | built |
| anomalyDetection | `anomaly` | — | built | built | built |
| businessInsights | `insights` | built | built | built | built |
| caseManagement | `case-management` | — | built | built | built |
| incidents | `ops` | — | built | built | built |
| integrationDelivery | `notify-channels` | — | built | built | built |
| laInvestigations | `ops` | — | built | built | built |
| linkAnalysis | `la-storage`, `la-core`, `la-api`, `geo-link` | built | built | built | built |
| multiEntity | `exchange` | — | built | built | built |
| predictiveAnalytics | `predictive` | — | built | built | built |
| premiumConnectors | `connectors-kafka` | — | built | built | built |
| realtimeDetection | `realtime` | — | built | built | built |
| reconciliation | `reconciliation` | — | built | built | built |
| regulatoryReporting | `regulatory-reporting` | — | built | built | built |
| scoringLists | `entity-list`, `scoring` | — | built | built | built |
| screening | `screening` | — | built | built | built |
| telecomIndustryPack | `telecom-asn1` | — | — | built | built |
| workflowSla | `ops` | — | built | built | built |

### Deployment posture

| Posture | Basic | Standard | Advanced | Preview |
|---|:-:|:-:|:-:|:-:|
| authMode | none | oidc | oidc | oidc |
| eventsBackend | parquet | parquet | parquet | parquet |
| objectsBackend | default | default | postgres | default |
| postgresSidecar | absent | bundled | bundled | bundled |

### Staged jars

| | Basic | Standard | Advanced | Preview |
|---|:-:|:-:|:-:|:-:|
| First-party jars (processor + connectors + optional + core thin) | 24 | 44 | 48 | 48 |
| of which core thin jars | 16 | 16 | 16 | 16 |
| Optional modules beyond Basic | 0 | 20 | 24 | 24 |
| Total on the classpath (+ third-party sidecars) | 24 | 45 | 49 | 49 |
<!-- offerings-matrix:end -->
