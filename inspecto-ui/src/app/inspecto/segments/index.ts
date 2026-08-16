/**
 * Segments — mapping a hierarchical parser's decoded record tree onto flat segment schemas, the step
 * that turns an `ingestable` plugin parser from previewable into loadable.
 *
 * Shared (not onboarding-owned) since the Pipelines Parse drawer needed the same editor: a feature may
 * not import another feature, so the editor lives here and each host supplies its own write path — the
 * `connection-form.dialog` relocation precedent.
 */
export * from './segment-drafts';
export * from './segments-editor.component';
