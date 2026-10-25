/** The component-registry kinds the reuse-graph loads (the backend `ComponentType`s). Pipelines are loaded
 *  separately (authored flows via `PipelinesService`) since they live in their own store, not `/components`.
 *  The saved investigation views joined with R1 (widget→view→dataset edges now derive).
 *
 *  Kept in its own import-free module so the eagerly-loaded host providers (`la-host.providers.ts`) can read it
 *  without pulling `registry.component` — and with it the graph library — into the main bundle (PERF-P7). */
export const REGISTRY_KINDS = [
    'dataset',
    'query',
    'widget',
    'dashboard',
    'grammar',
    'transform',
    'sink',
    'rule-template',
    'geo-map-view',
    'link-analysis-view',
];
