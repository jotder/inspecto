---
type: Concept
title: "On Inspecto — Architecture: the platform on one page"
description: "The second page of the developer track: Inspecto's data plane and control plane, Spaces, editions and the three gates, the extension points, and where an App plugs in — enough to design an App without reading the platform's source."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-read
---

# On Inspecto — Architecture: the platform on one page

The second developer page. It shows the parts of Inspecto an App touches and the parts it never needs to. Read
[Requirements](user-guide-develop-requirements.md) first.

## The picture

```
                ┌────────────────────────── one Inspecto server (one Java process) ─────────────────────────┐
  console ─────▶│ CONTROL PLANE   /api/v1  ── one envelope, an OpenAPI 3.1 contract, /spaces/{id}/… per Space│
  your code ───▶│   sign-in (Standard and up) · Roles and capabilities · config safety check · audit        │
                │──────────────────────────────────────────────────────────────────────────────────────────│
  files,        │ DATA PLANE (per Space)                                                                    │
  SFTP, DB, ───▶│   Collect ──▶ Parse ──▶ Shape (Pipelines of Steps) ──▶ Analyse ──▶ Act                    │
  object store, │   Collectors   typed     enrich, dedupe, join,          Jobs,       Alert Rules, Incidents, │
  Kafka         │                Parquet   write Datasets                 Measures    Cases, Action Requests  │
                │──────────────────────────────────────────────────────────────────────────────────────────│
                │ STORAGE  each Space's own folders: config (TOON files) · data (Parquet) · audit           │
                │          embedded DuckDB for every query; PostgreSQL optional for operational stores       │
                └───────────────────────────────────────────────────────────────────────────────────────────┘
       an App plugs in here ─▶  Space Templates (configuration only) · menu entries · required Add-ons
```

## The data plane

Every Space runs the same five stages:

1. **Collect.** Collectors bring files and streams in: local folders, SFTP, FTP and FTPS, databases, object stores
   (S3, Azure Blob, GCS) and Kafka, with duplicate and watermark tracking and a stability gate, so a half-written file
   is not read.
2. **Parse.** Files become typed records, written as Parquet; a row that does not fit is quarantined, not dropped.
3. **Shape.** Pipelines of Steps enrich, deduplicate and join, and write **Datasets**.
4. **Analyse.** Jobs and Queries read Datasets with SQL on an embedded DuckDB; there is no database to install.
5. **Act.** Expectations check the data; Alert Rules raise Alerts and Incidents; Cases group Incidents; Action
   Requests propose calls to other systems, always with a second person's approval.

An App supplies *configuration* for these stages — which Collector, which Pipeline, which Job, which Alert Rule —
and no code at all, unless it needs a new kind of Step, parser or connector (below).

## The control plane

Everything the console does goes through the **Control API**, served under `/api/v1`. Your scripts and integrations
use the same API.

- **One envelope.** Every answer is JSON with `data`, and `metadata`, `links` and `diagnostics` where they apply.
- **Per Space.** A Space's routes are addressed as `/api/v1/spaces/{id}/…`.
- **Safe writes.** Every configuration write passes the same safety check the console uses; a write with an error is
  refused with the finding, and `validate` reports the findings without saving. Versioned resources carry an `ETag`,
  and retryable writes accept an `Idempotency-Key`.
- **One contract.** The server serves its own OpenAPI 3.1 document at `/api/v1/openapi.json`:

```check
GET /openapi.json
as builder
expect status 200
expect openapi matches /^3\.1/
```

## Spaces

A **Space** is one isolated working area: its own configuration, data, audit trail and settings. A Space is created
from a **Space Template** — which is how an App's content reaches a user. See
[Space](../../module/config/user-guide-concept-space.md) and
[Space Template](../../module/config/user-guide-concept-space-template.md).

## Editions and the three gates

An **edition** — Basic, Standard or Advanced — is a *build* of one codebase that carries a set of modules; Add-ons
are modules some editions carry. A feature is usable only when three gates pass, and each refusal has its own answer:

| Gate | Decided by | A refused request answers |
|---|---|---|
| **Installed** | the edition's module set | `503 CAPABILITY_UNAVAILABLE` |
| **Enabled** | the Space's module settings | `404 MODULE_DISABLED` |
| **Permitted** | the user's Roles and capabilities | the route's authorization refusal |

Sign-in is not part of the base: Basic runs without it; Standard and Advanced sign users in through your identity
provider (OIDC). Your App must not assume either; it is held to every edition its Add-ons reach.

The server tells you what it carries. `/bootstrap` reports the edition, the version and the features this build
actually has:

```check
GET /bootstrap
as builder
expect status 200
expect data.edition exists
expect data.version exists
```

`/modules` lists every module with its state, the reason when it is not running, and what it provides:

```check
GET /modules
as builder
expect status 200
expect data.modules[*].id has processor
```

See [The editions on one page](../../../../EDITIONS.md) and
[Edition and Add-on](../product/user-guide-concept-edition-addon.md).

## The extension points

When configuration is not enough, a Java jar can add a new *kind* of thing. Each extension point is a Java interface
found through the standard `java.util.ServiceLoader`: you implement it and name your class in
`META-INF/services/<interface>` in your jar. Build only against interfaces and types marked `@PublicApi` — they keep
their shape within a major Platform version; anything unmarked may change in any release.

| You want a new… | Interface (`@PublicApi`) |
|---|---|
| Step in a Pipeline | `com.gamma.pipeline.exec.StepExecutor` (runs it) and `com.gamma.pipeline.PipelineNodeType` (describes it) |
| file format (parser) | `com.gamma.parse.ParserPlugin` |
| system to collect from | `com.gamma.acquire.CollectorConnectorFactory`, which makes a `CollectorConnector` |
| system to deliver to | `com.gamma.acquire.ExportConnectorFactory` |
| Job type | `com.gamma.job.JobTypeProvider` |
| unit of work over one delivered batch of files | `com.gamma.consignment.ConsignmentProcessor` |
| Decision Rule Consequence | `com.gamma.decision.ConsequenceProvider` |
| notification channel | `com.gamma.notify.NotificationChannel` |

How you build, package and load one is on the [Build](user-guide-develop-build.md) page.

## Where an App plugs in

| Layer | What it holds | Does your App change it? |
|---|---|---|
| **Base** modules | the engine, the control plane, storage | never |
| **Add-ons** | general capabilities: Incidents, Cases, Reconciliation, Scoring, Link Analysis … | no — the App *requires* them |
| **Industry** | a vertical's shared data: record types, Entity Types, terms | no — the App *reads* it |
| **App** | Space Templates, menu entries, docs, golden data | **yes — this is what you write** |
| **Product** | what a customer receives: chosen Apps on an edition | your App rides on one |

## Read next

- [Design an App](user-guide-develop-design.md)
- [How the pieces fit](../product/user-guide-concept-overview.md)
