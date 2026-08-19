/**
 * Schema — the `raw.fields[]` grid that names, types and selects the columns reaching the output, plus
 * the identifier/selector helpers that derive a first draft from a parsed sample.
 *
 * Shared (not onboarding-owned) since the Pipelines Parse drawer authors an output schema too
 * (definition-surface unification P4-2a): a feature may not import another feature, so the grid lives
 * here and each host supplies its own write path — the segments-editor relocation precedent.
 */
export * from './schema-fields-editor.component';
export * from './schema-metadata-grid.component';
