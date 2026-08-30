# Control Plane

The HTTP control surface + the runtime services around the [engine](../engine): the API and its versioned
v1 contract, queries, observability, the job scheduler, and multi-space hosting.

# Concepts

* [Control API](control-api.md) - the JDK `HttpServer`, the `dispatch` seam, the route families, the
  editions-aware auth model (auth-free core; Standard OIDC via SPIs).
* [Versioned API (/api/v1)](api-v1.md) - the v1 business contract: envelope, error-code catalog,
  ETag/`contentHash`, bootstrap, async runs, OpenAPI enforcement.
* [Queries](queries.md) - Query as a Component: the Query Library, `$`-Parameters, Result Set, and
  `POST /queries/{id}/run` on DuckDB.
* [Decision rules](decision-rules.md) - `/decision-rules` CRUD + sample-driven `simulate` over the
  `query-types` condition tree, evaluated by the shared `ConditionTree` engine (query-eval.ts parity).
* [Tags](tags.md) - the cross-entity label graph (D7): Tag vs Tag Assignment, the central
  `(tag, targetKind, targetId)` store, rename propagation, and the per-target gate that keeps a tag from
  ever becoming an access grant.
* [Events & metrics](events-metrics.md) - `EventLog` (synchronous bus), `MetricRegistry`, `StabilityGate`.
* [Signal backbone](signal-backbone.md) - the canonical `Signal` envelope (`Ref`, 6-level `Severity`), projected to notification templating, AG-UI streaming, A2UI artifacts, agent context tools, and the gated agentic write path (`invoke` confirm-then-apply).
* [Jobs](jobs.md) - `JobService` cron/event/manual scheduling, the off-bus trigger handoff, and the
  v1 async run model (202 + `runId`).
* [Job vs Pipeline Step — capability boundary](job-vs-step.md) - the full capability comparison behind the
  binding in-motion/at-rest rule: what the two genuinely share, where they diverge, the four things that
  blur the line, and the as-built gaps in a Job Pack's reach.
* [Platform Services — the plugin envelope](platform-services.md) - the named seam a plugin is granted
  engine facilities through: a flat typed lookup filtered by a declared `requires:` list, validated at
  registration and substituted under a dry run, plus the pack scaffolder and `PackTestHarness`.
* [Multi-space](multi-space.md) - `SpaceManager`/`SpaceContext`/`SpaceMigrator` and the MDC-based singleton isolation.
* [Exchange — cross-space sharing](exchange-sharing.md) - grant-mediated, read-only Dataset/Widget sharing across Spaces; offer/request/approve ledger, snapshot/live delivery, version pin + drift.
* [API stability policy](api-stability.md) - the Java `@PublicApi` surface contract (the HTTP counterpart is [api-v1](api-v1.md)).
* [Metadata bundle](metadata-bundle.md) - export/preview/import of authored config across installs (`BundleRoutes`; schema in `docs/api/schemas/`).
* [Onboarding authoring](onboarding-authoring.md) - the draft lifecycle (`/config/*`), stateless sample previews, the pipeline/enrichment register pair, and `produces: reference`.
* [Pipeline closure — what belongs to a pipeline](pipeline-related.md) - `GET /pipelines/{name}/related`:
  the server-side answer to the two-way reference problem (a pipeline points OUT to its schema/mapping/
  grammar; enrichments, jobs and datasets point IN), joining the existing `PipelineDependents` scan with
  `referencedFiles()`. Connections deliberately excluded.
* [Pipeline identity: rename & Save-as-template](pipeline-identity.md) - `label` (display-only) vs
  `save-as-template` (a non-runnable, fully isolated sibling) vs `rename` (the full id migration —
  ledger, audit trail, DuckDB mirror, dependent configs); the shared findings-diff gotcha and the one
  deliberate exception to "`PipelineRunGuard.isRunning` is never a gate."
