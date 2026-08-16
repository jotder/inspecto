/**
 * What a **mapping rule** is, shared by every surface that authors one.
 *
 * A rule is exactly `{targetColumn, sourceExpression, transformType}` — `MappingCsv` drops any other
 * key on read, so there is no per-rule `format`/`pattern` field and the specialised types pack their
 * parameters into `sourceExpression` as `|`-delimited positions.
 *
 * Two surfaces author rules today (the Pipelines Load drawer, structurally; the Components mapping
 * editor, as a free-text grid) and the offline mock validates them. They must agree on the vocabulary,
 * so it lives here rather than being restated per feature — a feature may not import another feature.
 */

/**
 * `TransformCompiler.TRANSFORM_TYPES`, mirrored. `DIRECT` is also the implicit default: blank, omitted
 * and any case-spelling of `DIRECT` all mean DIRECT, and anything else non-blank throws at compile.
 */
export const TRANSFORM_TYPES = ['DIRECT', 'EXPR', 'CONCAT_DT', 'FILENAME_DATE'] as const;

/**
 * `FILENAME_DATE` may only write this column — enforced in THREE places server-side
 * (`TransformCompiler`, `MappingRules`, and the offline mock's validator), so authoring anything else
 * is a guaranteed 422.
 */
export const FILENAME_DATE_TARGET = 'EVENT_DATE';
