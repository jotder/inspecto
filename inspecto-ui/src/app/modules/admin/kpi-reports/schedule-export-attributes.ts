import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * Attribute declarations for scheduling a Dataset export — drives `<inspecto-schema-form>` in
 * {@link ScheduleExportDialog}. A scheduled export IS a Job (`type: 'report'`, `scope: dataset`); no new
 * entity — `dataset`, `format` and `recipients` live in the job's `params`. (SCHEDULE-EXPORT-DIALOG-DEAD-1:
 * `ReportJob` has no dashboard scope, so the old dashboard payload delivered nothing.)
 */
export const SCHEDULE_EXPORT_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'name',
        label: 'Schedule id',
        type: 'string',
        tier: 'required',
        pattern: '[A-Za-z0-9][A-Za-z0-9._-]*',
        placeholder: 'e.g. daily_sales_export',
        help: 'Letters, digits, dot, dash, underscore; start alphanumeric.',
    },
    {
        key: 'dataset',
        label: 'Dataset',
        type: 'autocomplete',
        tier: 'required',
        placeholder: 'e.g. cdr_daily',
        help: 'The Dataset whose rows are exported (up to 10,000).',
    },
    {
        key: 'format',
        label: 'Export format',
        type: 'select',
        tier: 'required',
        default: 'csv',
        options: [
            { value: 'csv', label: 'CSV' },
            { value: 'xlsx', label: 'Excel workbook' },
        ],
    },
    {
        key: 'scheduleMode',
        label: 'Trigger',
        type: 'select',
        tier: 'required',
        default: 'cron',
        options: [
            { value: 'cron', label: 'Cron schedule' },
            { value: 'manual', label: 'Manual only' },
        ],
    },
    {
        key: 'cron',
        label: 'Cron expression',
        type: 'string',
        tier: 'required',
        dependsOn: { key: 'scheduleMode', equals: 'cron' },
        default: '0 0 6 * * *',
        pattern: '\\S+(\\s+\\S+){4,5}',
        help: '5 or 6 fields (sec min hour day month weekday)',
    },
    {
        key: 'recipients',
        label: 'Recipients (comma-separated)',
        type: 'string',
        tier: 'optional',
        required: false,
        placeholder: 'ops@example.com, finance@example.com',
        help: 'Emailed the file location when the export completes (the file is not attached).',
    },
    { key: 'enabled', label: 'Enabled (armed)', type: 'boolean', tier: 'optional', default: true },
];
