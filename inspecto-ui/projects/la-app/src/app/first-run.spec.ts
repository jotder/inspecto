import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService, SampleSpace, Space, SpacesService } from '@inspecto/core/api';
import { SessionService } from '@inspecto/core/auth/session.service';
import { SpaceFormDialog } from '@inspecto/core/spaces/space-form.dialog';
import { FirstRun } from './first-run';

const MADE: SampleSpace = {
    id: 'sample',
    displayName: 'S',
    description: '',
    createdAt: '',
    samples: {
        pipelines: [
            { pipeline: 'mule_transfers', sampleFiles: 3, status: 'loaded', filesLoaded: 3, filesQuarantined: 0 },
        ],
    },
};

function create(o: { authMode?: 'none' | 'oidc'; canAdminister?: boolean; made?: SampleSpace; created?: Space } = {}) {
    const selectSpace = vi.fn();
    const createSample = vi.fn(() => of(o.made ?? MADE));
    const open = vi.fn(() => ({ afterClosed: () => of(o.created) }));
    TestBed.configureTestingModule({
        providers: [
            provideRouter([]),
            { provide: SpacesService, useValue: { selectSpace, createSample } },
            { provide: SessionService, useValue: { authMode: signal(o.authMode ?? 'none') } },
            { provide: LensService, useValue: { canAdminister: signal(o.canAdminister ?? false) } },
            { provide: MatDialog, useValue: { open } },
        ],
    });
    const run = TestBed.inject(FirstRun);
    const reload = vi.spyOn(run, 'reload').mockImplementation(() => undefined);
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    return { run, selectSpace, createSample, open, reload, nav };
}

describe('FirstRun', () => {
    it('anyone may create a Space with no sign-in; signed in, only with canAdminister', () => {
        expect(create().run.canCreate()).toBe(true);
        TestBed.resetTestingModule();
        expect(create({ authMode: 'oidc' }).run.canCreate()).toBe(false);
        TestBed.resetTestingModule();
        expect(create({ authMode: 'oidc', canAdminister: true }).run.canCreate()).toBe(true);
    });

    it('Try the sample creates la-sample with its data, then enters it on Link Analysis with the sample view', async () => {
        const { run, createSample, selectSpace, nav, reload } = create();
        run.trySample().subscribe();
        expect(createSample).toHaveBeenCalledWith('la-sample', expect.any(String));
        expect(selectSpace).toHaveBeenCalledWith('sample');
        expect(nav).toHaveBeenCalledWith('/link-analysis?view=mule_layering_ring');
        await Promise.resolve();
        expect(reload).toHaveBeenCalled();
    });

    it('a sample whose data did not load is NOT entered (the page names the failure)', () => {
        const failed: SampleSpace = {
            ...MADE,
            samples: { pipelines: [{ ...MADE.samples!.pipelines[0], status: 'failed', filesLoaded: 0, error: 'x' }] },
        };
        const { run, selectSpace } = create({ made: failed });
        run.trySample().subscribe();
        expect(selectSpace).not.toHaveBeenCalled();
    });

    it('Create a Space asks the name and kind of data, then enters the new Space on Add data', () => {
        const { run, open, selectSpace, nav } = create({
            created: { id: 'calls', displayName: 'Calls', description: '', createdAt: '' },
        });
        run.createSpace();
        expect(open).toHaveBeenCalledWith(SpaceFormDialog, expect.objectContaining({ data: { dataKind: true } }));
        expect(selectSpace).toHaveBeenCalledWith('calls');
        expect(nav).toHaveBeenCalledWith('/add-data');
    });

    it('each guide step opens its screen; Space opens the create dialog', () => {
        const { run, nav, open } = create();
        for (const [step, url] of [
            ['data', '/add-data'],
            ['columns', '/entities?tab=columns'],
            ['graph', '/link-analysis'],
            ['list', '/entity-lists'],
        ] as const) {
            run.openStep(step);
            expect(nav).toHaveBeenLastCalledWith(url);
        }
        run.openStep('space');
        expect(open).toHaveBeenCalled();
    });
});
