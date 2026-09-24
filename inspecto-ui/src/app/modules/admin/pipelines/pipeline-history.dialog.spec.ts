import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PipelineHistory, PipelineHistoryDiff, PipelinesService } from 'app/inspecto/api/pipelines.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineHistoryDialog } from './pipeline-history.dialog';

const HISTORY: PipelineHistory = {
    pipeline: 'orders',
    keep: 50,
    total: 3,
    versions: [
        { version: 7, savedAt: '2026-09-24T10:00:00Z', bytes: 120 },
        { version: 6, savedAt: '2026-09-24T09:00:00Z', bytes: 118 },
        { version: 5, savedAt: '2026-09-24T08:00:00Z', bytes: 110 },
    ],
};

function diffOf(from: number, to: number | 'current'): PipelineHistoryDiff {
    return {
        pipeline: 'orders',
        from,
        to,
        added: 1,
        removed: 1,
        coarse: false,
        lines: [
            { op: 'context', text: 'name: orders' },
            { op: 'remove', text: 'description: old' },
            { op: 'add', text: 'description: new' },
        ],
    };
}

function create(history = of(HISTORY)) {
    const historyDiff = vi.fn((_: string, from: number, to?: number) => of(diffOf(from, to ?? 'current')));
    TestBed.configureTestingModule({
        imports: [PipelineHistoryDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: MAT_DIALOG_DATA, useValue: { id: 'orders' } },
            { provide: PipelinesService, useValue: { history: () => history, historyDiff } },
        ],
    });
    const fixture = TestBed.createComponent(PipelineHistoryDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, historyDiff, el: fixture.nativeElement as HTMLElement };
}

describe('PipelineHistoryDialog', () => {
    it('lists the versions newest first and diffs the newest against the one before it', () => {
        const { c, el, historyDiff } = create();
        const rows = Array.from(el.querySelectorAll('ul[aria-label="Saved versions"] button'));
        expect(rows.map((b) => b.querySelector('.font-medium')?.textContent)).toEqual(['v7', 'v6', 'v5']);
        expect(rows[0].getAttribute('aria-pressed')).toBe('true');
        expect(historyDiff).toHaveBeenCalledWith('orders', 6, 7);
        expect(c.summary()).toBe('v6 → v7: 1 line(s) added, 1 removed.');
    });

    it('renders removed lines as <del> and added lines as <ins>, read-only', () => {
        const { el } = create();
        const region = el.querySelector('[role="region"][aria-label="Diff"]')!;
        expect(region.querySelector('del')?.textContent).toContain('description: old');
        expect(region.querySelector('ins')?.textContent).toContain('description: new');
        expect(region.querySelectorAll('input, textarea, button').length).toBe(0);
    });

    it('compares against the current config when asked, and for the oldest version', () => {
        const { c, historyDiff } = create();
        c.setCompare('current');
        expect(historyDiff).toHaveBeenLastCalledWith('orders', 7);
        c.setCompare('previous');
        c.select(5); // the oldest kept version has no previous one
        expect(c.compare()).toBe('current');
        expect(historyDiff).toHaveBeenLastCalledWith('orders', 5);
        expect(c.previousOf(5)).toBeNull();
    });

    it('shows the empty state when nothing has been saved yet', () => {
        const { el, historyDiff } = create(of({ ...HISTORY, total: 0, versions: [] }));
        expect(el.querySelector('inspecto-empty-state')?.textContent).toContain('No saved versions yet');
        expect(historyDiff).not.toHaveBeenCalled();
    });

    it('surfaces a failed read as an error alert', () => {
        const { el } = create(
            throwError(() => new HttpErrorResponse({ status: 404, error: { error: { message: 'no pipeline' } } })),
        );
        expect(el.querySelector('inspecto-alert')).not.toBeNull();
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
