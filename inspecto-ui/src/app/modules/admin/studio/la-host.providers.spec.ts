import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SessionService } from 'app/inspecto/api';
import { LA_DATASETS, LA_FEATURES, LaDataset } from '@inspecto/link-analysis/la-host';
import { of } from 'rxjs';
import { DatasetsService } from './datasets/datasets.service';
import { provideLaHostServices } from './la-host.providers';

/** The Inspecto host's side of LA_FEATURES: the flags ARE the SessionService signals, not copies of them. */
describe('provideLaHostServices - LA_FEATURES', () => {
    it('maps ops / exchange / geoLink onto SessionService.opsEnabled / exchangeEnabled / geoLinkEnabled, live', () => {
        const session = { opsEnabled: signal(false), exchangeEnabled: signal(true), geoLinkEnabled: signal(false) };
        TestBed.configureTestingModule({
            providers: [{ provide: SessionService, useValue: session }, ...provideLaHostServices()],
        });
        const f = TestBed.inject(LA_FEATURES);
        expect([f.ops(), f.exchange(), f.geoLink()]).toEqual([false, true, false]);
        session.opsEnabled.set(true);
        session.geoLinkEnabled.set(true);
        expect([f.ops(), f.exchange(), f.geoLink()]).toEqual([true, true, true]);
    });
});

/** ONBOARDING-FIRST-RUN-1 C6a: a Reference's identifying column reaches Link Analysis (Studio keeps it in `extra`). */
describe('provideLaHostServices - LA_DATASETS', () => {
    it('lifts a Reference identifying column out of extra, on list and get; other Datasets unchanged', () => {
        const stores = {
            id: 'stores',
            name: 'stores',
            kind: 'reference',
            primaryEntityType: 'store',
            extra: { identifyingColumn: 'store_id' },
        };
        const calls = { id: 'calls', name: 'calls', kind: 'physical', extra: {} };
        TestBed.configureTestingModule({
            providers: [
                ...provideLaHostServices(),
                { provide: DatasetsService, useValue: { list: () => of([stores, calls]), get: () => of(stores) } },
            ],
        });
        const svc = TestBed.inject(LA_DATASETS);
        let listed: LaDataset[] = [];
        let one: LaDataset | null = null;
        svc.list().subscribe((d) => (listed = d));
        svc.get('stores').subscribe((d) => (one = d));
        expect(listed.map((d) => [d.id, d.kind, d.identifyingColumn])).toEqual([
            ['stores', 'reference', 'store_id'],
            ['calls', 'physical', undefined],
        ]);
        expect(one!.identifyingColumn).toBe('store_id');
    });
});
