import { HttpErrorResponse } from '@angular/common/http';
import { Injector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { DbBrowserService } from 'app/inspecto/api/db-browser.service';
import { datasetColumnOptionLoader } from './dataset-column-option-loader';

function setup(content: Record<string, unknown> | null) {
    const get = vi.fn((_t: string, id: string) =>
        content
            ? of({ type: 'dataset', name: id, ref: `dataset/${id}`, content })
            : throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    const table = vi.fn(() =>
        of({
            columns: [
                { name: 'ORDER_ID', type: 'BIGINT' },
                { name: 'REGION', type: 'VARCHAR' },
            ],
            rows: [],
            truncated: false,
        }),
    );
    TestBed.configureTestingModule({
        providers: [
            { provide: ComponentsService, useValue: { get } },
            { provide: DbBrowserService, useValue: { table } },
        ],
    });
    const loader = runInInjectionContext(TestBed.inject(Injector), () => datasetColumnOptionLoader('dataset'));
    return { loader, get, table };
}

describe('datasetColumnOptionLoader', () => {
    it('declared columns win — no store probe', async () => {
        const { loader, table } = setup({
            kind: 'physical',
            physicalRef: 'orders',
            columns: [{ name: 'amount', type: 'number' }],
        });
        expect(await loader({ dataset: 'orders_dataset' })).toEqual([{ value: 'amount', label: 'amount (number)' }]);
        expect(table).not.toHaveBeenCalled();
    });

    it('probes the physicalRef store, not the Dataset id', async () => {
        const { loader, get, table } = setup({ kind: 'physical', physicalRef: 'orders' });
        const opts = await loader({ dataset: 'orders_dataset' });
        expect(get).toHaveBeenCalledWith('dataset', 'orders_dataset');
        expect(table).toHaveBeenCalledWith(expect.objectContaining({ name: 'orders', limit: 1 }));
        expect(opts.map((o) => o.value)).toEqual(['ORDER_ID', 'REGION']);
    });

    it('an unknown Dataset yields no suggestions and does not throw', async () => {
        const { loader, table } = setup(null);
        await expect(loader({ dataset: 'nope' })).resolves.toEqual([]);
        expect(table).not.toHaveBeenCalled();
    });

    it('no Dataset chosen yet → no request', async () => {
        const { loader, get } = setup({});
        expect(await loader({ dataset: '  ' })).toEqual([]);
        expect(get).not.toHaveBeenCalled();
    });
});
