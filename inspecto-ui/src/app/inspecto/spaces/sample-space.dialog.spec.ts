import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SampleSpace, SpacesService, SpaceTemplateInfo } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SampleSpaceDialog } from './sample-space.dialog';

const T = (id: string, extra: Partial<SpaceTemplateInfo> = {}): SpaceTemplateInfo => ({
    id,
    name: id.toUpperCase(),
    tagline: `${id} tagline`,
    description: '',
    icon: '',
    contents: [],
    creatable: true,
    samples: true,
    ...extra,
});

const GALLERY = [
    T('orders-starter'),
    T('la-sample'),
    T('la-showcase', { creatable: false }),
    T('blank', { samples: false }),
];

function create(made: SampleSpace | Error = { id: 'sample', displayName: 'S', description: '', createdAt: '' }) {
    const close = vi.fn();
    const createSample = vi.fn(() => (made instanceof Error ? throwError(() => made) : of(made)));
    TestBed.configureTestingModule({
        imports: [SampleSpaceDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { preferred: 'la-sample' } },
            {
                provide: SpacesService,
                useValue: { templates: () => of(GALLERY), createSample, availableSpaces: signal([]) },
            },
        ],
    });
    const fixture = TestBed.createComponent(SampleSpaceDialog);
    fixture.detectChanges();
    return { fixture, close, createSample };
}

describe('SampleSpaceDialog', () => {
    it('offers only creatable templates that ship samples, the preferred one picked (no violations)', async () => {
        const { fixture } = create();
        const el = fixture.nativeElement as HTMLElement;
        const names = Array.from(el.querySelectorAll('mat-radio-button')).map(
            (r) => r.textContent!.trim().split(/\s+/)[0],
        );
        expect(names).toEqual(['ORDERS-STARTER', 'LA-SAMPLE']);
        expect(fixture.componentInstance.choice.value).toBe('la-sample');
        await expectNoA11yViolations(el);
    });

    it('one click creates, loads and closes with the Space', () => {
        const { fixture, close, createSample } = create();
        fixture.componentInstance.create();
        expect(createSample).toHaveBeenCalledWith('la-sample', 'LA-SAMPLE');
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 'sample' }));
    });

    it('a Pipeline that did not load keeps the dialog open, names it, and still offers the Space (no violations)', async () => {
        const made: SampleSpace = {
            id: 'sample',
            displayName: 'S',
            description: '',
            createdAt: '',
            samples: {
                pipelines: [
                    {
                        pipeline: 'mule_transfers',
                        sampleFiles: 3,
                        status: 'failed',
                        filesLoaded: 0,
                        filesQuarantined: 3,
                        error: '3 file(s) were quarantined',
                    },
                ],
            },
        };
        const { fixture, close } = create(made);
        fixture.componentInstance.create();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(close).not.toHaveBeenCalled();
        expect(el.textContent).toContain('mule_transfers: 3 file(s) were quarantined');
        const open = Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('Open the Space'))!;
        open.click();
        expect(close).toHaveBeenCalledWith(made);
        await expectNoA11yViolations(el);
    });

    it('a refused create says why in place', () => {
        const { fixture, close } = create(
            Object.assign(new Error('x'), { error: { error: { message: 'no samples' } } }),
        );
        fixture.componentInstance.create();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(fixture.nativeElement.querySelector('inspecto-alert')).not.toBeNull();
    });
});
