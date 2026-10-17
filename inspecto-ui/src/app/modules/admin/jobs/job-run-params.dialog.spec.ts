import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { JobsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobRunParamsDialog } from './job-run-params.dialog';

const decl = (o: object) => ({ required: false, deduce: '', default: '', description: '', ...o });

function create(trigger = vi.fn(() => of({ runId: 'r1' })), extra: object[] = []) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [JobRunParamsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { name: 'j1', type: 'anomaly.score' } },
            { provide: MatDialogRef, useValue: ref },
            {
                provide: JobsService,
                useValue: {
                    describeType: () =>
                        of({
                            id: 'anomaly.score',
                            title: '',
                            description: '',
                            emits: [],
                            artifacts: [],
                            requires: [],
                            parameters: [
                                decl({ name: 'as_of', type: 'DATE', tier: 'REQUIRED' }),
                                decl({ name: 'limit', type: 'INTEGER', tier: 'REQUIRED', default: '10' }),
                                ...extra,
                            ],
                        }),
                    get: () => of({ name: 'j1', type: 'anomaly.score', params: { limit: 5 } }),
                    trigger,
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(JobRunParamsDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, trigger };
}

describe('JobRunParamsDialog', () => {
    it('does not offer a config-only parameter (the Run would silently ignore an override)', async () => {
        const { fixture, c } = create(undefined, [decl({ name: 'sink_dataset', type: 'STRING', configOnly: true })]);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(c.specs().map((s) => s.key)).toEqual(['as_of', 'limit']);
    });

    it('sends only the fields the user changed, then closes with the run id', async () => {
        const { fixture, c, ref, trigger } = create();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(c.specs().length).toBe(2);
        const form = c.paramForm!.form;
        form.get('as_of')!.setValue('2026-08-06');
        form.get('as_of')!.markAsDirty();
        c.run();
        expect(trigger).toHaveBeenCalledWith('j1', { as_of: '2026-08-06' });
        expect(ref.close).toHaveBeenCalledWith({ runId: 'r1' });
    });

    it('shows a refusal inline and stays open', async () => {
        const { fixture, c, ref } = create(
            vi.fn(() => throwError(() => new HttpErrorResponse({ status: 422, error: { error: 'as_of: bad date' } }))),
        );
        await fixture.whenStable();
        fixture.detectChanges();
        c.run();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        expect(c.error()).not.toBe('');
        expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
    });

    it('has no axe violations', async () => {
        const { fixture } = create();
        await fixture.whenStable();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
