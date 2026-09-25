import { describe, expect, it } from 'vitest';
import { biQueryBody } from '../dataset-result.service';
import { ControlValues } from '../viz-types';
import { TABLE_PLUGIN } from './index';

const CTX = { datasetId: 'open_items', sourceName: 'open_items' };
const VALUES: ControlValues = {
    x: [{ field: 'control' }],
    y: [{ field: 'exposure_sar', agg: 'sum' }],
};

// R2-02: a table Widget had no default sort — its rows came back in query order, so the top item by exposure
// was not on page 1. `options.tableSort` must reach the query's ORDER BY, not just the grid.
describe('TABLE_PLUGIN — options.tableSort', () => {
    it('orders the query by a measure column, and that order crosses the /bi/query wire', () => {
        const spec = TABLE_PLUGIN.buildQuery(VALUES, {
            ...CTX,
            options: { tableSort: { field: 'sum_exposure_sar', dir: 'desc' } },
        });
        expect(spec.orderBy).toEqual([{ field: 'sum_exposure_sar', dir: 'desc' }]);
        expect(biQueryBody(spec)?.orderBy).toEqual([{ field: 'sum_exposure_sar', dir: 'desc' }]);
    });

    it('orders by a dimension column ascending', () => {
        const spec = TABLE_PLUGIN.buildQuery(VALUES, {
            ...CTX,
            options: { tableSort: { field: 'control', dir: 'asc' } },
        });
        expect(spec.orderBy).toEqual([{ field: 'control', dir: 'asc' }]);
    });

    it('sends no ORDER BY without the option, or for a column the table does not return', () => {
        expect(TABLE_PLUGIN.buildQuery(VALUES, CTX).orderBy).toBeUndefined();
        const stale = TABLE_PLUGIN.buildQuery(VALUES, {
            ...CTX,
            options: { tableSort: { field: 'sum_loss_sar', dir: 'desc' } },
        });
        expect(stale.orderBy).toBeUndefined();
    });
});
