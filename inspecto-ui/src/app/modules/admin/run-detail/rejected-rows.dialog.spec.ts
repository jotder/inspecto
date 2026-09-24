import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { GammaConfigService } from '@gamma/services/config';
import { LensService, replayRejectsErrorMessage, RunsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RejectedRowsDialog } from './rejected-rows.dialog';

const REJECTS = {
    pipeline: 'cdr',
    file: 'feed.csv',
    errorsFile: 'feed_errors.csv',
    rowCount: 2,
    truncated: false,
    rows: [
        { line_number: '3', reason: 'column count', raw_line: 'short1,3.0' },
        { line_number: '5', reason: 'column count', raw_line: 'short2,4.0' },
    ],
};
const REPLAYED = {
    pipeline: 'cdr',
    file: 'feed.csv',
    replayFile: 'feed__replay_1a2b3c4d.csv',
    batchId: 'default_abc_0001',
    status: 'SUCCESS',
    records: 2,
    outputRows: 2,
    errorRows: 0,
    error: null,
};

function httpError(status: number, message: string): HttpErrorResponse {
    return new HttpErrorResponse({ status, error: { error: { message } } });
}

function create(opts: { canOperate?: boolean; confirm?: boolean; replay?: () => unknown } = {}) {
    const runs = {
        rejectedRows: vi.fn((_p: string, _f: string) => of(REJECTS)),
        replayRejects: vi.fn((_p: string, _f: string) => (opts.replay ? opts.replay() : of(REPLAYED))),
    };
    const toastr = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
    const confirm = { confirm: vi.fn((_message: string, _title?: string) => Promise.resolve(opts.confirm ?? true)) };
    const dialog = { open: vi.fn((_c: unknown, _cfg?: unknown) => ({ afterClosed: () => of(undefined) })) };
    TestBed.configureTestingModule({
        imports: [RejectedRowsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { pipeline: 'cdr', file: 'feed.csv' } },
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: RunsService, useValue: runs },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: LensService, useValue: { canOperateRuns: () => opts.canOperate ?? true } },
            InspectoGridThemeService,
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    // `<inspecto-data-table>` injects the real MatDialog — a plain useValue provider is silently ignored.
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const fixture = TestBed.createComponent(RejectedRowsDialog);
    fixture.detectChanges();
    return { fixture, runs, toastr, confirm, dialog };
}

const button = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLButtonElement | null;

describe('RejectedRowsDialog — replay rejected records (X4)', () => {
    it('offers the replay on a loaded reject file, confirms, and shows the new Consignment', async () => {
        const { fixture, runs, confirm, toastr, dialog } = create();
        const el = fixture.nativeElement as HTMLElement;
        const btn = button(el, 'replay-rejects');
        expect(btn).not.toBeNull();
        expect(btn!.disabled).toBe(false);

        await fixture.componentInstance.replay();
        fixture.detectChanges();

        const message = confirm.confirm.mock.calls[0][0] as string;
        expect(message).toContain('Only these rejected lines');
        expect(message).toContain('new Consignment');
        expect(message).toContain('ONCE per reject file');
        expect(message).toContain('before 2026-09-25');
        expect(message).toContain('apostrophes');
        expect(runs.replayRejects).toHaveBeenCalledWith('cdr', 'feed.csv');
        expect(toastr.success).toHaveBeenCalled();
        const result = el.querySelector('[data-testid="replay-result"]')!;
        expect(result.textContent).toContain('default_abc_0001');
        expect(button(el, 'replay-rejects')!.disabled).toBe(true); // once per reject file

        button(el, 'open-replay-batch')!.click();
        expect(dialog.open).toHaveBeenCalledWith(
            expect.anything(),
            expect.objectContaining({ data: { pipeline: 'cdr', batchId: 'default_abc_0001' } }),
        );
        await expectNoA11yViolations(el);
    });

    it('does nothing when the confirm is dismissed', async () => {
        const { fixture, runs } = create({ confirm: false });
        await fixture.componentInstance.replay();
        expect(runs.replayRejects).not.toHaveBeenCalled();
    });

    it('is disabled WITH a reason when the caller lacks canOperateRuns', async () => {
        const { fixture, runs } = create({ canOperate: false });
        const el = fixture.nativeElement as HTMLElement;
        expect(button(el, 'replay-rejects')!.disabled).toBe(true);
        expect(el.querySelector('[data-testid="replay-reason"]')!.textContent).toContain('canOperateRuns');
        await fixture.componentInstance.replay();
        expect(runs.replayRejects).not.toHaveBeenCalled();
    });

    it('maps a 409 to "already replayed" and keeps the button usable state honest', async () => {
        const { fixture, toastr } = create({
            replay: () => throwError(() => httpError(409, "'feed.csv' was already replayed from this sidecar")),
        });
        await fixture.componentInstance.replay();
        fixture.detectChanges();
        const msg = toastr.error.mock.calls[0][0] as string;
        expect(msg).toContain('already replayed');
        expect(msg).toContain('land them twice');
        expect(fixture.componentInstance.replaying()).toBe(false);
    });

    it('a FAILED replay warns, stores nothing and leaves the replay available (the claim was released)', async () => {
        const { fixture, toastr } = create({
            replay: () => of({ ...REPLAYED, status: 'FAILED', batchId: null, error: 'disk full' }),
        });
        await fixture.componentInstance.replay();
        fixture.detectChanges();
        expect(toastr.warning.mock.calls[0][0]).toContain('disk full');
        expect(fixture.componentInstance.replayed()).toBeNull();
        expect(button(fixture.nativeElement, 'replay-rejects')!.disabled).toBe(false);
    });
});

describe('replayRejectsErrorMessage', () => {
    it('names the next step for each refusal and appends the server reason', () => {
        expect(replayRejectsErrorMessage(httpError(409, 'x'), 'f.csv')).toContain('already replayed');
        const unreplayable = replayRejectsErrorMessage(
            httpError(422, 'record replay covers delimited-CSV pipelines only'),
            'f.csv',
        );
        expect(unreplayable).toContain('cannot be replayed');
        expect(unreplayable).toContain('delimited-CSV pipelines only');
        expect(replayRejectsErrorMessage(httpError(403, "'file' must be a bare file name"), 'f.csv')).toContain(
            'bare file name',
        );
        expect(replayRejectsErrorMessage(httpError(500, 'boom'), 'f.csv')).toBe('boom');
    });
});
