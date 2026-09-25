import { channelMeasure } from '../query-spec';
import { ControlValues, QuerySpec, VizPlugin, VizProps } from '../viz-types';
import { QueryCtx } from './plugin-helpers';

/**
 * Table plugin — renders the spec result in the shared data-table (ag-Grid). Groups by the chosen dimensions
 * and aggregates the chosen measures; with no fields chosen it shows the raw rows. Always available (`fit:{}`).
 */
function buildTableQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const groupBy = (values.x ?? []).map((cv) => cv.field);
    const measures = (values.y ?? []).map(channelMeasure);
    // `options.tableSort` orders the query itself, so the row limit keeps the top rows. Only a result column can be
    // ordered by — a field left over from an earlier mapping is dropped rather than failing the whole query.
    const sort = ctx.options?.tableSort;
    const sortable = !!sort?.field && (groupBy.includes(sort.field) || measures.some((m) => m.id === sort.field));
    return {
        datasetId: ctx.datasetId,
        sourceName: ctx.sourceName,
        groupBy,
        measures,
        filters: ctx.filters ?? null,
        ...(sort && sortable ? { orderBy: [{ field: sort.field, dir: sort.dir }] } : {}),
    };
}

function transformTable(rows: Record<string, unknown>[]): VizProps {
    return { labels: [], series: [], rows, columns: rows.length ? Object.keys(rows[0]) : [] };
}

export const TABLE_PLUGIN: VizPlugin = {
    meta: { type: 'table', label: 'Table', icon: 'heroicons_outline:table-cells', fit: {} },
    controls: [
        { channel: 'x', label: 'Dimensions', acceptRoles: ['dimension', 'temporal'], multiple: true },
        { channel: 'y', label: 'Measures', acceptRoles: ['measure'], isMeasure: true, multiple: true },
    ],
    buildQuery: buildTableQuery,
    transformProps: transformTable,
    render: { kind: 'aggrid' },
};
