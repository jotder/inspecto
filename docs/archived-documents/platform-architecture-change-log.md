# Log

## 2026-07-07
* **Consolidated**: bundle moved `docs/okf-backend/` → `docs/okf/backend/` under the new
  [consolidated bundle](../okf/product/index.md) (frontend at `../frontend/`, new `../agentic/` section);
  `flow-graph/` renamed [`pipeline-graph/`](../okf/product/index.md) per the canonical vocabulary.
* **Refreshed** to the shipped W1–W7 state: new [api-v1](../okf/product/module/processor/api-v1.md) +
  [queries](../okf/product/module/processor/spec-queries.md) + [security module](../okf/product/module/oidc/architecture.md) concepts;
  [control-api](../okf/product/module/processor/api-control-api.md), [jobs](../okf/product/module/processor/spec-jobs.md) (async 202+runId),
  [events-metrics](../okf/product/module/event/spec-events-metrics.md) (legacy-usage metric),
  [auth-security](../okf/product/module/access/admin-guide-auth-security.md) / [editions-model](../okf/product/layer/platform/architecture-editions.md)
  (`inspecto-security` is BUILT), [modules](../okf/product/index.md) + [agent](../okf/product/module/agent/spec-assist-agent.md)
  (agent-kernel → vendored kernel + eoiagent), and
  [component-registry](../okf/product/module/processor/spec-component-registry.md) (widened writable kinds, ETag/ContentHash).

## 2026-06-28
* **Created**: Initial OKF v0.1 bundle for the Inspecto Java backend — [Overview](../okf/product/layer/platform/architecture-overview.md),
  [Architecture](../okf/product/layer/platform/architecture.md), the [Modules](../okf/product/index.md), [Engine](../okf/product/index.md), [Acquisition](../okf/product/index.md),
  [Control plane](../okf/product/index.md), [Flow-graph](../okf/product/index.md) *(dir since renamed)*, [Components](../okf/product/index.md), [Config](../okf/product/index.md),
  [Editions](../okf/product/index.md), [Agent](../okf/product/index.md), [Build & run](../okf/product/index.md), and [Gotchas](../okf/product/index.md) sets.
  Consolidated from `docs/PROJECT_NOTES.md`, the topic docs (`ADVANCED_GUIDE`, `EDITIONS`, `configuration`,
  `data_acquisition_framework`, `flow-graph-design`, `flow-live-execution-plan`, `performance`,
  `parsing-options-reference`, `delimited-grammar-design`, `plugins`, `BRANCHING`) and a source sweep of the
  `inspecto/`, `providers/inspecto-connectors/`, `features/inspecto-agent/`, and `providers/inspecto-agent-hosted/` modules.
