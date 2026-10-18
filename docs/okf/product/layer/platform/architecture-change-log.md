# Log

## 2026-07-07
* **Consolidated**: bundle moved `docs/okf-backend/` → `docs/okf/backend/` under the new
  [consolidated bundle](../../index.md) (frontend at `../frontend/`, new `../agentic/` section);
  `flow-graph/` renamed [`pipeline-graph/`](../../index.md) per the canonical vocabulary.
* **Refreshed** to the shipped W1–W7 state: new [api-v1](../../module/processor/api-v1.md) +
  [queries](../../module/processor/spec-queries.md) + [security module](../../module/oidc/architecture.md) concepts;
  [control-api](../../module/processor/api-control-api.md), [jobs](../../module/processor/spec-jobs.md) (async 202+runId),
  [events-metrics](../../module/event/spec-events-metrics.md) (legacy-usage metric),
  [auth-security](../../module/access/admin-guide-auth-security.md) / [editions-model](architecture-editions-model.md)
  (`inspecto-security` is BUILT), [modules](../../index.md) + [agent](../../module/agent/spec-assist-agent.md)
  (agent-kernel → vendored kernel + eoiagent), and
  [component-registry](../../module/processor/spec-component-registry.md) (widened writable kinds, ETag/ContentHash).

## 2026-06-28
* **Created**: Initial OKF v0.1 bundle for the Inspecto Java backend — [Overview](architecture-overview.md),
  [Architecture](architecture.md), the [Modules](../../index.md), [Engine](../../index.md), [Acquisition](../../index.md),
  [Control plane](../../index.md), [Flow-graph](../../index.md) *(dir since renamed)*, [Components](../../index.md), [Config](../../index.md),
  [Editions](../../index.md), [Agent](../../index.md), [Build & run](../../index.md), and [Gotchas](../../index.md) sets.
  Consolidated from `docs/PROJECT_NOTES.md`, the topic docs (`ADVANCED_GUIDE`, `EDITIONS`, `configuration`,
  `data_acquisition_framework`, `flow-graph-design`, `flow-live-execution-plan`, `performance`,
  `parsing-options-reference`, `delimited-grammar-design`, `plugins`, `BRANCHING`) and a source sweep of the
  `inspecto/`, `providers/inspecto-connectors/`, `features/inspecto-agent/`, and `providers/inspecto-agent-hosted/` modules.
