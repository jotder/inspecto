import { AttributeSpec } from './attribute-spec';

/**
 * **The** schema-form spec for a pipeline's `collector:` block — flat keys (`__` path separator, see
 * `onboarding-config-utils`) over the Stage-1 `collector:` TOON block (see `PipelineConfigParser`;
 * every key here is engine-real). A pipeline with no `collector:` block reads its local inbox
 * (`dirs.poll`) — exactly what the defaults describe.
 *
 * <p>Shared (moved here 2026-07-31, W2/U-D) because **two** features author this one block: Onboarding's
 * Collection stage and the Pipelines editor's `acquisition` node (`node-attributes.ts`). It lived under
 * `catalog/onboarding/` while the Pipelines editor kept a second, hand-written table — and that table
 * had drifted into keys the engine does not read (`recursive` as a boolean, `min_age_seconds`) where
 * this one has the real ones (`recursive_depth`, `stability__window`). One concern, one table: a
 * feature-local copy is how that drift happened.
 */
export const COLLECTOR_ATTRIBUTES: AttributeSpec[] = [
    // `connector` is deliberately NOT a spec: it is derived (local inbox ⇒ `local`, otherwise the
    // picked Connection's own connector) and injected by the pane at save time. Asking for it
    // invites a mismatch — CollectorConnectors.forConfig dispatches on `collector.connector` and
    // hands that factory the profile named by `collector.connection` without checking they agree.
    {
        key: 'connection',
        label: 'Connection',
        type: 'autocomplete',
        tier: 'required',
        required: false,
        help: 'Saved Connection profile — it carries the connector type (SFTP, Azure Blob, Kafka, Database).',
    },
    {
        key: 'include',
        label: 'Include patterns',
        type: 'string',
        tier: 'optional',
        placeholder: '*.csv, orders_*.txt',
        help: 'Glob/regex discovery patterns; comma-separate multiple. Blank = the pipeline file pattern.',
    },
    {
        key: 'discovery',
        label: 'Discovery',
        type: 'select',
        tier: 'optional',
        default: 'poll',
        options: [
            { value: 'poll', label: 'Poll' },
            { value: 'watch', label: 'Watch (filesystem events)' },
        ],
    },
    {
        key: 'duplicate__mode',
        label: 'Duplicate detection',
        type: 'select',
        tier: 'optional',
        default: 'path',
        options: [
            { value: 'path', label: 'By path' },
            { value: 'metadata', label: 'By metadata (size + mtime)' },
            { value: 'checksum', label: 'By checksum' },
            { value: 'etag', label: 'By remote ETag' },
        ],
        help: 'File-level duplicate policy — how a re-seen file is recognised.',
    },
    {
        key: 'post_action__on_success',
        label: 'After success',
        type: 'select',
        tier: 'optional',
        default: 'RETAIN',
        options: [
            { value: 'RETAIN', label: 'Retain' },
            { value: 'DELETE', label: 'Delete' },
            { value: 'MOVE', label: 'Move to archive' },
            { value: 'RENAME', label: 'Rename' },
            { value: 'TAG', label: 'Tag' },
        ],
    },
    { key: 'exclude', label: 'Exclude patterns', type: 'string', tier: 'advanced', placeholder: '*.tmp' },
    { key: 'recursive_depth', label: 'Recursive depth', type: 'number', tier: 'advanced', min: 0, help: 'Blank = unbounded.' },
    {
        key: 'duplicate__on_change',
        label: 'On changed duplicate',
        type: 'select',
        tier: 'advanced',
        options: [
            { value: 'reprocess', label: 'Reprocess' },
            { value: 'skip', label: 'Skip' },
        ],
        help: 'What to do when a known file re-appears changed.',
    },
    {
        key: 'guarantee',
        label: 'Delivery guarantee',
        type: 'select',
        tier: 'advanced',
        options: [
            { value: 'BEST_EFFORT', label: 'Best effort' },
            { value: 'AT_LEAST_ONCE', label: 'At least once' },
            { value: 'EXACTLY_ONCE', label: 'Exactly once' },
        ],
    },
    { key: 'stability__window', label: 'Stability window', type: 'string', tier: 'advanced', placeholder: '5s', help: 'Wait for a file to stop growing before collecting it.' },
    { key: 'post_action__archive_path', label: 'Archive path', type: 'string', tier: 'advanced', help: 'Target directory when "After success" is Move.' },
];

/**
 * ⚠ **There is no dedup subset any more** (collector-config unification, 2026-08-04). D9 had split
 * `duplicate__*` onto a `transform.dedup.fingerprint` node; that node was REMOVED because file
 * duplicate detection executes inside the `CollectorProcessor` poll cycle (`ledgerFilter` reads
 * `collector.duplicate`) — it never had a runtime as a transform, so the split told the operator
 * the check happens after collection, which is not where it happens. Both authoring surfaces —
 * Onboarding's Collection stage and the Pipelines `acquisition` node — now render this table WHOLE.
 */
