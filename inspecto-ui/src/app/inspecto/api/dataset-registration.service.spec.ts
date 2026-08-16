import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ComponentsService } from './components.service';
import { DatasetRegistrationService, datasetManualHint } from './dataset-registration.service';

/**
 * The Stream→Dataset hop at go-live, extracted from the onboarding shell in P6-b so the Pipelines
 * editor's toolbar activation performs the identical one (and so it outlives the shell's deletion in
 * P6-e). The posture these cases pin: idempotent, and it NEVER fails the activation that already
 * succeeded — every error path resolves to `failed`, none of them throws.
 */
describe('DatasetRegistrationService', () => {
    let components: { list: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };

    function make(): DatasetRegistrationService {
        TestBed.configureTestingModule({
            providers: [{ provide: ComponentsService, useValue: components }],
        });
        return TestBed.inject(DatasetRegistrationService);
    }

    beforeEach(() => {
        components = { list: vi.fn(() => of([])), create: vi.fn(() => of({})) };
    });

    it('registers the Dataset over the store, naming the store as its source', async () => {
        const res = await new Promise((r) => make().ensure('orders', 'Orders').subscribe(r));

        expect(res).toEqual({ status: 'created', store: 'orders' });
        expect(components.create).toHaveBeenCalledWith(
            'dataset',
            expect.objectContaining({
                id: 'orders',
                name: 'Orders',
                kind: 'physical',
                // ⚠ blank sourceName used to fall through to a default naming nothing, so the dataset
                // silently read zero rows everywhere — the store IS the source.
                sourceName: 'orders',
                physicalRef: 'orders',
            }),
        );
    });

    it('is idempotent — an existing Dataset on that store wins, whatever its id', async () => {
        components.list = vi.fn(() => of([{ content: { physicalRef: 'orders' } }]));

        const res = await new Promise((r) => make().ensure('orders', 'Orders').subscribe(r));

        expect(res).toEqual({ status: 'exists', store: 'orders' });
        expect(components.create).not.toHaveBeenCalled();
    });

    it('downgrades a failed create to a result, never an error — the pipeline is already live', async () => {
        components.create = vi.fn(() => throwError(() => new Error('boom')));

        const res = await new Promise((r) => make().ensure('orders', 'Orders').subscribe(r));

        expect(res).toEqual({ status: 'failed', store: 'orders' });
    });

    it('downgrades an unreadable registry the same way', async () => {
        components.list = vi.fn(() => throwError(() => new Error('boom')));

        const res = await new Promise((r) => make().ensure('orders', 'Orders').subscribe(r));

        expect(res).toEqual({ status: 'failed', store: 'orders' });
        expect(components.create).not.toHaveBeenCalled();
    });

    it('gives both hosts the same recovery instruction', () => {
        expect(datasetManualHint('orders')).toContain('physical reference "orders"');
    });
});
