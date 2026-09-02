import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { JobRunRow } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobRunDetailDialog } from './job-run-detail.dialog';

const RUN: JobRunRow = {
    runId: 'r-100',
    job: 'rollup',
    type: 'ENRICH',
    trigger: 'schedule',
    startTime: '2026-06-17 10:00:00',
    endTime: '2026-06-17 10:00:01',
    status: 'SUCCESS',
    durationMs: 1500,
    message: 'ok',
};

function create(run: JobRunRow = RUN) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [JobRunDetailDialog],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: MAT_DIALOG_DATA, useValue: run },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(JobRunDetailDialog);
    fixture.detectChanges();
    return { fixture, ref };
}

describe('JobRunDetailDialog', () => {
    /** X2 cross-lane provenance: what an at-rest run READ, linked only where the producer pipeline is known. */
    it('lists the Consignments the run derived from, linking only those with a known pipeline', () => {
        const { fixture } = create({
            ...RUN,
            derivedFrom: [
                { consignmentId: 'c-a', pipeline: 'cdr_etl', tableName: 'cdr' },
                { consignmentId: 'c-b', pipeline: null, tableName: 'cdr' },
            ],
        });
        const el: HTMLElement = fixture.nativeElement;
        const section = el.querySelector('[data-testid="derived-from"]');
        expect(section?.textContent).toContain('2 Consignments');
        expect(section?.textContent).toContain('c-a');
        expect(section?.textContent).toContain('c-b');
        const links = Array.from(section!.querySelectorAll('a'));
        expect(links.map((a) => a.textContent?.trim())).toEqual(['open cdr_etl']);
    });

    it('shows no derived-from section on a run that read nothing', () => {
        const { fixture } = create();
        expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="derived-from"]')).toBeNull();
    });

    it('renders the run fields with a formatted duration', () => {
        const { fixture } = create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('rollup');
        expect(text).toContain('r-100');
        expect(text).toContain('1.5s');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
