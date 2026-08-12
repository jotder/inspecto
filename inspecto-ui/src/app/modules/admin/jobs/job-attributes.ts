import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Job kind's attribute declarations (W2 pilot) — drives `<inspecto-schema-form>` in
 * {@link JobFormDialog}. Tier assignments per the attribute audit: identity + trigger are required,
 * arming is optional, `catchUp` is advanced (it was silently missing from the old hand-built form).
 *
 * Keys here are the UI's own (camelCase); `jobToWire` maps them onto the server's snake_case job
 * section — never send these names to the API directly.
 */
// 'name' (the job id) is asked at save time (ui-design-review R9 — name-at-save), not declared here;
// see JobFormDialog's `saveForm`.
export const JOB_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'type', label: 'Type', type: 'select', tier: 'required', default: 'enrich',
        help: 'The kind of work; its declared parameters drive the form below.',
        // The registered Job Type ids (matches the backend registry / GET /jobs/types).
        options: [
            { value: 'enrich', label: 'enrich' },
            { value: 'report', label: 'report' },
            { value: 'maintenance', label: 'maintenance' },
            { value: 'pipeline', label: 'pipeline' },
            { value: 'sql.template', label: 'sql.template' },
        ],
    },
    {
        key: 'scheduleMode', label: 'Trigger', type: 'select', tier: 'required', default: 'cron',
        options: [
            { value: 'cron', label: 'Cron schedule' },
            { value: 'event', label: 'On pipeline (event-driven)' },
            { value: 'signal', label: 'On signal' },
            { value: 'manual', label: 'Manual only' },
        ],
    },
    {
        key: 'cron', label: 'Cron expression', type: 'string', tier: 'required',
        dependsOn: { key: 'scheduleMode', equals: 'cron' },
        default: '0 0 6 * * *',
        pattern: '\\S+(\\s+\\S+){4,5}',
        placeholder: '0 0 6 * * *',
        help: '5 or 6 fields (sec min hour day month weekday)',
    },
    {
        key: 'onPipeline', label: 'On pipeline', type: 'autocomplete', tier: 'required',
        dependsOn: { key: 'scheduleMode', equals: 'event' },
        placeholder: 'e.g. cdr_ingest (comma-separate for several)',
        // The engine takes a comma list (JobConfig.onPipelines) — advertise it instead of hiding it.
        help: 'Runs when this pipeline commits a batch. Comma-separate to watch several upstreams.',
    },
    {
        key: 'onPipelineGate', label: 'Fire when', type: 'select', tier: 'optional',
        dependsOn: { key: 'scheduleMode', equals: 'event' },
        default: 'any',
        options: [
            { value: 'any', label: 'Any upstream commits' },
            { value: 'all', label: 'All upstreams have committed' },
        ],
        // multi-location-ingest.md: the all-gate's pending set is in-memory — a restart waits for a
        // full cycle again (a late run, never a wrong one).
        help: 'With several upstreams: fire per commit, or once every named upstream has committed since the last firing (then re-arm).',
    },
    {
        key: 'onSignal', label: 'On signal', type: 'string', tier: 'required',
        dependsOn: { key: 'scheduleMode', equals: 'signal' },
        placeholder: 'e.g. dataset.write',
        // `Signals.matchesType`: exact (case-insensitive) or a `prefix.*` glob. Free text on purpose —
        // there is no signal-type catalog endpoint, and a job may be armed for a signal not yet emitted.
        help: 'The signal type to fire on — exact (dataset.write) or a prefix glob (dataset.*).',
    },
    {
        key: 'when', label: 'Only when', type: 'string', tier: 'optional',
        dependsOn: { key: 'scheduleMode', equals: 'signal' },
        placeholder: "e.g. $signal.dataset == 'premium_cdr_view'",
        help: "Optional guard over the firing signal's payload; the job runs only when it holds. Leave blank to run on every match.",
    },
    { key: 'enabled', label: 'Enabled (armed)', type: 'boolean', tier: 'optional', default: true },
    {
        key: 'catchUp', label: 'Catch up missed fires', type: 'boolean', tier: 'advanced', default: false,
        help: 'Run once at startup when a scheduled fire was missed while the server was down.',
    },
];
