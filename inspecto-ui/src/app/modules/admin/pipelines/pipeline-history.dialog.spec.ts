import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PipelineHistory, PipelineHistoryDiff, PipelinesService } from 'app/inspecto/api/pipelines.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
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

function create(
    history = of(HISTORY),
    opts: { confirm?: boolean; restore?: ReturnType<PipelinesService['restoreHistory']>; dirty?: boolean } = {},
) {
    const historyDiff = vi.fn((_: string, from: number, to?: number) => of(diffOf(from, to ?? 'current')));
    const restoreHistory = vi.fn(
        () => opts.restore ?? of({ pipeline: 'orders', restored: 6, version: 8, path: 'orders_pipeline.toon' }),
    );
    const confirmDestructive = vi.fn(async () => opts.confirm ?? true);
    const close = vi.fn();
    TestBed.configureTestingModule({
        imports: [PipelineHistoryDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { id: 'orders', dirty: opts.dirty } },
            { provide: PipelinesService, useValue: { history: () => history, historyDiff, restoreHistory } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive } },
        ],
    });
    const fixture = TestBed.createComponent(PipelineHistoryDialog);
    fixture.detectChanges();
    return {
        fixture,
        c: fixture.componentInstance,
        historyDiff,
        restoreHistory,
        confirmDestructive,
        close,
        el: fixture.nativeElement as HTMLElement,
    };
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

    it('restores the selected version after a confirm and closes with the result', async () => {
        const { c, el, restoreHistory, confirmDestructive, close } = create();
        c.select(6);
        expect(el.textContent).toContain('The newest 50 versions are kept');
        await c.restore();
        expect(confirmDestructive).toHaveBeenCalled();
        expect(restoreHistory).toHaveBeenCalledWith('orders', 6);
        expect(close).toHaveBeenCalledWith({
            pipeline: 'orders',
            restored: 6,
            version: 8,
            path: 'orders_pipeline.toon',
        });
    });

    it('warns that unsaved editor edits are discarded when the tab is dirty', async () => {
        const { c, confirmDestructive } = create(of(HISTORY), { dirty: true, confirm: false });
        await c.restore();
        expect(String((confirmDestructive.mock.calls[0] as unknown[])[0])).toContain('unsaved edits');
    });

    it('does nothing when the confirm is declined', async () => {
        const { c, restoreHistory, close } = create(of(HISTORY), { confirm: false });
        await c.restore();
        expect(restoreHistory).not.toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();
    });

    it('shows a refused restore and stays open', async () => {
        const refused = throwError(
            () =>
                new HttpErrorResponse({
                    status: 409,
                    error: { error: { message: "version 6 declares pipeline id 'old' (saved before a rename)" } },
                }),
        );
        const { c, fixture, el, close } = create(of(HISTORY), { restore: refused });
        await c.restore();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(c.restoring()).toBe(false);
        expect(el.querySelector('inspecto-alert[title="Could not restore"]')?.textContent).toContain('before a rename');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
